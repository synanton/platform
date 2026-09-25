package org.synanton.gateway.gpu;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.gateway.config.GatewayProperties;
import org.synanton.gpu.v1.*;
import org.synanton.llm.RerankClient;
import org.synanton.llm.RerankRequest;
import org.synanton.llm.RerankResponse;

import java.util.List;
import java.util.UUID;

/**
 * GpuRerankAdapter wraps GpuExecutionClient for reranking workloads.
 * Returns empty response when the GPU plane is degraded and the caller
 * must fall back to the CPU path.
 */
@Component
public class GpuRerankAdapter implements RerankClient {

    private static final Logger log = LoggerFactory.getLogger(GpuRerankAdapter.class);

    private final GpuExecutionClient client;
    private final GpuExecutionClientProperties props;
    private final GatewayProperties.Rerank rerankProps;
    private final ObjectMapper objectMapper;
    private final ModelResolver modelResolver;

    public GpuRerankAdapter(
            GpuExecutionClient client,
            GpuExecutionClientProperties props,
            GatewayProperties.Rerank rerankProps,
            ObjectMapper objectMapper,
            ModelResolver modelResolver) {
        this.client = client;
        this.props = props;
        this.rerankProps = rerankProps;
        this.objectMapper = objectMapper;
        this.modelResolver = modelResolver;
    }

    /**
     * Rerank with explicit tenant ID for GPU routing.
     * This is the primary method used by the query flow.
     */
    public RerankResponse rerank(RerankRequest request, String tenantId) {
        String requestId = UUID.randomUUID().toString();
        byte[] payload;
        try {
            payload = buildPayload(request, tenantId);
        } catch (Exception e) {
            log.warn("GPU rerank: payload build failed, degrading to CPU: {}", e.getMessage());
            return fallbackRerank(request);
        }

        String model = request.model() != null && !request.model().isEmpty()
                ? request.model()
                : modelResolver.resolveModel(tenantId, Operation.RERANK);

        ExecutionRequest exeRequest = ExecutionRequest.newBuilder()
                .setRequestId(requestId)
                .setTenantId(tenantId)
                .setModel(model)
                .setModelVersion(props.getModelVersion())
                .setOperation(Operation.RERANK)
                .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                .setProvider(modelResolver.resolveProvider(tenantId, model, Operation.RERANK))
                .build();

        return executeWithRetry(exeRequest, request.passages().size());
    }

    @Override
    public RerankResponse rerank(RerankRequest request) {
        // Interface method - uses default tenant for backward compatibility
        return rerank(request, "default");
    }

    private RerankResponse executeWithRetry(ExecutionRequest request, int passageCount) {
        int maxAttempts = props.getRetry().getMaxAttempts();
        int backoffBaseMs = props.getRetry().getBackoffBaseMs();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            long start = System.currentTimeMillis();

            ExecutionResponse response;
            try {
                response = client.executeRerank(request);
            } catch (StatusRuntimeException e) {
                long latencyMs = System.currentTimeMillis() - start;
                Status.Code code = e.getStatus().getCode();

                if (code == Status.Code.DEADLINE_EXCEEDED) {
                    log.warn("GPU rerank timeout after {}ms (request={}), degrading to CPU",
                            latencyMs, request.getRequestId());
                    return fallbackRerank(null);
                }
                if (code == Status.Code.UNAVAILABLE) {
                    log.warn("GPU plane unavailable (request={}), degrading to CPU", request.getRequestId());
                    return fallbackRerank(null);
                }

                if (!GpuExecutionClient.isRetryableDenial(e)) {
                    attempt = maxAttempts - 1; // non-retryable denial (Plan §16): fail now, never retry
                }
                log.warn("GPU rerank gRPC error {} code={} (request={}) attempt {}/{}",
                        code, GpuExecutionClient.canonicalCode(e), request.getRequestId(), attempt + 1, maxAttempts);
                if (attempt == maxAttempts - 1) {
                    return new RerankResponse(List.of(), 0, 0, latencyMs, 0, 0);
                }
                sleep(backoff(backoffBaseMs, attempt));
                continue;
            }

            long latencyMs = System.currentTimeMillis() - start;

            if (response.getState() == ExecutionState.SUCCESS) {
                return handleSuccess(response, latencyMs, passageCount);
            }

            if (response.getState() == ExecutionState.TIMEOUT) {
                return new RerankResponse(List.of(), 0, 0, latencyMs, 0, 0);
            }

            if (response.getState() == ExecutionState.FAILED && response.hasError()) {
                ErrorReason reason = response.getError().getReason();

                if (reason == ErrorReason.GPU_UNAVAILABLE || reason == ErrorReason.GPU_CAPACITY_EXCEEDED) {
                    log.warn("GPU rerank degraded (reason={}, request={}), falling back to CPU",
                            reason, request.getRequestId());
                    return fallbackRerank(null);
                }

                if (reason == ErrorReason.MODEL_NOT_READY && response.getError().getRetryable()
                        && attempt < maxAttempts - 1) {
                    long delay = backoff(backoffBaseMs, attempt);
                    log.debug("GPU MODEL_NOT_READY (request={}), retry {}/{} after {}ms",
                            request.getRequestId(), attempt + 1, maxAttempts, delay);
                    sleep(delay);
                    continue;
                }

                log.warn("GPU rerank terminal failure (reason={}, code={}, upstream_request_id={}, request={})",
                        reason, GpuExecutionClient.canonicalCode(response), response.getUpstreamRequestId(),
                        request.getRequestId());
                return new RerankResponse(List.of(), 0, 0, latencyMs, 0, 0);
            }

            return new RerankResponse(List.of(), 0, 0, latencyMs, 0, 0);
        }

        return fallbackRerank(null);
    }

    private RerankResponse handleSuccess(ExecutionResponse response, long latencyMs, int passageCount) {
        try {
            byte[] resultBytes = response.getResult().toByteArray();
            OpenAiRerankResponse parsed = objectMapper.readValue(resultBytes, OpenAiRerankResponse.class);

            List<RerankResponse.RerankResult> results = new java.util.ArrayList<>();
            if (parsed.results() != null) {
                for (OpenAiRerankResponse.ResultItem item : parsed.results()) {
                    results.add(new RerankResponse.RerankResult(
                            item.index(),
                            item.relevance_score(),
                            item.document() != null ? item.document().text() : ""
                    ));
                }
            }

            int promptTokens = 0;
            if (parsed.usage() != null) {
                promptTokens = parsed.usage().prompt_tokens();
            }

            return new RerankResponse(results, 0, 0, latencyMs, promptTokens, 0);
        } catch (Exception e) {
            log.warn("GPU rerank: response parse error: {}", e.getMessage());
            return new RerankResponse(List.of(), 0, 0, latencyMs, 0, 0);
        }
    }

    private RerankResponse fallbackRerank(RerankRequest request) {
        return new RerankResponse(List.of(), 0, 0, 0, 0, 0);
    }

    private byte[] buildPayload(RerankRequest request, String tenantId) throws Exception {
        String model = modelResolver.resolveModel(tenantId, Operation.RERANK);
        OpenAiRerankRequest rerankRequest = new OpenAiRerankRequest(
                model,
                request.query(),
                request.passages(),
                request.topN() > 0 ? request.topN() : rerankProps.topN()
        );
        return objectMapper.writeValueAsBytes(rerankRequest);
    }

    private long backoff(int baseMs, int attempt) {
        return (long) (baseMs * Math.pow(2, attempt));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── Internal OpenAI-compat JSON structures ───────────────────────────────

    private record OpenAiRerankRequest(
            String model,
            String query,
            List<String> documents,
            int top_n
    ) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiRerankResponse(
            List<ResultItem> results,
            Usage usage
    ) {
        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        record ResultItem(
                int index,
                double relevance_score,
                Document document
        ) {
            @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
            record Document(String text) {}
        }

        @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
        record Usage(
                int prompt_tokens,
                int total_tokens
        ) {}
    }
}