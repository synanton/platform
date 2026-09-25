package org.synanton.gateway.gpu;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.gateway.config.GatewayProperties;
import org.synanton.gpu.v1.*;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.LlmClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * GpuEmbeddingAdapter wraps GpuExecutionClient for embedding workloads.
 * Returns Optional.empty() when the GPU plane is degraded and the caller
 * must fall back to the CPU LlmClient path.
 */
@Component
public class GpuEmbeddingAdapter implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GpuEmbeddingAdapter.class);

    private final GpuExecutionClient client;
    private final GpuExecutionClientProperties props;
    private final GatewayProperties.Embedding embedProps;
    private final ObjectMapper objectMapper;
    private final ModelResolver modelResolver;

    public GpuEmbeddingAdapter(
            GpuExecutionClient client,
            GpuExecutionClientProperties props,
            GatewayProperties.Embedding embedProps,
            ObjectMapper objectMapper,
            ModelResolver modelResolver) {
        this.client = client;
        this.props = props;
        this.embedProps = embedProps;
        this.objectMapper = objectMapper;
        this.modelResolver = modelResolver;
    }

    /**
     * Embed with explicit tenant ID for GPU routing.
     * This is the primary method used by the query flow.
     */
    public EmbedResponse embed(EmbedRequest request, String tenantId) {
        String requestId = UUID.randomUUID().toString();
        byte[] payload;
        try {
            payload = buildPayload(request, tenantId);
        } catch (Exception e) {
            log.warn("GPU embedding: payload build failed, degrading to CPU: {}", e.getMessage());
            return fallbackEmbed(request);
        }

        String model = request.model() != null && !request.model().isEmpty()
                ? request.model()
                : modelResolver.resolveModel(tenantId, Operation.EMBED);

        ExecutionRequest exeRequest = ExecutionRequest.newBuilder()
                .setRequestId(requestId)
                .setTenantId(tenantId)
                .setModel(model)
                .setModelVersion(props.getModelVersion())
                .setOperation(Operation.EMBED)
                .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                .setProvider(modelResolver.resolveProvider(tenantId, model, Operation.EMBED))
                .build();

        return executeWithRetry(exeRequest, request.inputs().size());
    }

    @Override
    public EmbedResponse embed(EmbedRequest request) {
        // Interface method - uses default tenant for backward compatibility
        return embed(request, "default");
    }

    private EmbedResponse executeWithRetry(ExecutionRequest request, int inputCount) {
        int maxAttempts = props.getRetry().getMaxAttempts();
        int backoffBaseMs = props.getRetry().getBackoffBaseMs();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            long start = System.currentTimeMillis();

            ExecutionResponse response;
            try {
                response = client.executeEmbed(request);
            } catch (StatusRuntimeException e) {
                long latencyMs = System.currentTimeMillis() - start;
                Status.Code code = e.getStatus().getCode();

                if (code == Status.Code.DEADLINE_EXCEEDED) {
                    log.warn("GPU embedding timeout after {}ms (request={}), degrading to CPU",
                            latencyMs, request.getRequestId());
                    return fallbackEmbed(null);
                }
                if (code == Status.Code.UNAVAILABLE) {
                    log.warn("GPU plane unavailable (request={}), degrading to CPU", request.getRequestId());
                    return fallbackEmbed(null);
                }

                if (!GpuExecutionClient.isRetryableDenial(e)) {
                    attempt = maxAttempts - 1; // non-retryable denial (Plan §16): fail now, never retry
                }
                log.warn("GPU embedding gRPC error {} code={} (request={}) attempt {}/{}",
                        code, GpuExecutionClient.canonicalCode(e), request.getRequestId(), attempt + 1, maxAttempts);
                if (attempt == maxAttempts - 1) {
                    return new EmbedResponse(List.of(), 0, 0, latencyMs, 0, 0);
                }
                sleep(backoff(backoffBaseMs, attempt));
                continue;
            }

            long latencyMs = System.currentTimeMillis() - start;

            if (response.getState() == ExecutionState.SUCCESS) {
                return handleSuccess(response, latencyMs, inputCount);
            }

            if (response.getState() == ExecutionState.TIMEOUT) {
                return new EmbedResponse(List.of(), 0, 0, latencyMs, 0, 0);
            }

            if (response.getState() == ExecutionState.FAILED && response.hasError()) {
                ErrorReason reason = response.getError().getReason();

                if (reason == ErrorReason.GPU_UNAVAILABLE || reason == ErrorReason.GPU_CAPACITY_EXCEEDED) {
                    log.warn("GPU embedding degraded (reason={}, request={}), falling back to CPU",
                            reason, request.getRequestId());
                    return fallbackEmbed(null);
                }

                if (reason == ErrorReason.MODEL_NOT_READY && response.getError().getRetryable()
                        && attempt < maxAttempts - 1) {
                    long delay = backoff(backoffBaseMs, attempt);
                    log.debug("GPU MODEL_NOT_READY (request={}), retry {}/{} after {}ms",
                            request.getRequestId(), attempt + 1, maxAttempts, delay);
                    sleep(delay);
                    continue;
                }

                log.warn("GPU embedding terminal failure (reason={}, code={}, upstream_request_id={}, request={})",
                        reason, GpuExecutionClient.canonicalCode(response), response.getUpstreamRequestId(),
                        request.getRequestId());
                return new EmbedResponse(List.of(), 0, 0, latencyMs, 0, 0);
            }

            return new EmbedResponse(List.of(), 0, 0, latencyMs, 0, 0);
        }

        return fallbackEmbed(null);
    }

    private EmbedResponse handleSuccess(ExecutionResponse response, long latencyMs, int inputCount) {
        try {
            // Shared codec (gpu-client): vectors ordered by data[].index, count checked, so
            // a short or garbled result degrades to the CPU path instead of leaking partial data.
            var parsed = new org.synanton.gpu.client.GpuEmbedCodec(objectMapper)
                    .parse(response.getResult().toByteArray(), inputCount);
            return new EmbedResponse(parsed.embeddings(), 0, 0, latencyMs, parsed.promptTokens(), 0);
        } catch (Exception e) {
            log.warn("GPU embedding: response parse error: {}", e.getMessage());
            return new EmbedResponse(List.of(), 0, 0, latencyMs, 0, 0);
        }
    }

    private EmbedResponse fallbackEmbed(EmbedRequest request) {
        // Return empty response to signal fallback to CPU path
        return new EmbedResponse(List.of(), 0, 0, 0, 0, 0);
    }

    private byte[] buildPayload(EmbedRequest request, String tenantId) {
        String model = modelResolver.resolveModel(tenantId, Operation.EMBED);
        return new org.synanton.gpu.client.GpuEmbedCodec(objectMapper).payload(model, request.inputs());
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        // This adapter is for embeddings only; delegate to fallback CPU path
        return null;
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
}
