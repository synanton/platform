package org.synanton.gateway.gpu;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.gateway.config.GatewayProperties;
import org.synanton.gateway.synthesis.PromptBuilder;
import org.synanton.gateway.synthesis.SynthesisResult;
import org.synanton.gpu.v1.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

// GpuSynthesisAdapter wraps GpuExecutionClient for synthesis workloads.
// Returns Optional.empty() when the GPU plane is degraded and the caller
// must fall back to the CPU LlmClient path.
public class GpuSynthesisAdapter {

    private static final Logger log = LoggerFactory.getLogger(GpuSynthesisAdapter.class);
    private static final Random JITTER = new Random();

    private final GpuExecutionClient client;
    private final GpuExecutionClientProperties props;
    private final GatewayProperties.Synthesis synthProps;
    private final ObjectMapper objectMapper;

    public GpuSynthesisAdapter(
            GpuExecutionClient client,
            GpuExecutionClientProperties props,
            GatewayProperties.Synthesis synthProps,
            ObjectMapper objectMapper) {
        this.client = client;
        this.props = props;
        this.synthProps = synthProps;
        this.objectMapper = objectMapper;
    }

    // Attempt GPU synthesis. Returns:
    //   Optional.empty()           → GPU unavailable/degraded; caller falls back to CPU path
    //   Optional.of(Ok)            → GPU succeeded
    //   Optional.of(Error/Timeout) → GPU returned a terminal error; propagate to caller
    public Optional<SynthesisResult> synthesise(
            PromptBuilder.PromptInput input,
            String tenantId,
            Map<String, String> traceContext) {

        String requestId = UUID.randomUUID().toString();
        byte[] payload;
        try {
            payload = buildPayload(input);
        } catch (Exception e) {
            log.warn("GPU synthesis: payload build failed, degrading to CPU: {}", e.getMessage());
            return Optional.empty();
        }

        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setRequestId(requestId)
                .setTenantId(tenantId)
                .setModel(synthProps.model())
                .setModelVersion(props.getModelVersion())
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                .putAllTraceContext(traceContext)
                .build();

        return executeWithRetry(request);
    }

    private Optional<SynthesisResult> executeWithRetry(ExecutionRequest request) {
        int maxAttempts = props.getRetry().getMaxAttempts();
        int backoffBaseMs = props.getRetry().getBackoffBaseMs();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            long start = System.currentTimeMillis();

            ExecutionResponse response;
            try {
                response = client.execute(request);
            } catch (StatusRuntimeException e) {
                long latencyMs = System.currentTimeMillis() - start;
                Status.Code code = e.getStatus().getCode();

                if (code == Status.Code.DEADLINE_EXCEEDED) {
                    // Per GPU contract: callers MUST check GetStatus after DEADLINE_EXCEEDED.
                    // GPU-4 will add full post-timeout reconciliation. For now we degrade to CPU.
                    log.warn("GPU synthesis timeout after {}ms (request={}), degrading to CPU",
                            latencyMs, request.getRequestId());
                    return Optional.empty();
                }
                if (code == Status.Code.UNAVAILABLE) {
                    log.warn("GPU plane unavailable (request={}), degrading to CPU", request.getRequestId());
                    return Optional.empty();
                }

                log.warn("GPU synthesis gRPC error {} (request={}) attempt {}/{}",
                        code, request.getRequestId(), attempt + 1, maxAttempts);
                if (attempt == maxAttempts - 1) {
                    return Optional.of(new SynthesisResult.Error(
                            "GPU synthesis gRPC error: " + e.getStatus().getDescription(), latencyMs));
                }
                sleep(backoff(backoffBaseMs, attempt));
                continue;
            }

            long latencyMs = System.currentTimeMillis() - start;

            if (response.getState() == ExecutionState.SUCCESS) {
                return handleSuccess(response, latencyMs, request.getRequestId());
            }

            if (response.getState() == ExecutionState.TIMEOUT) {
                return Optional.of(new SynthesisResult.Timeout(latencyMs));
            }

            if (response.getState() == ExecutionState.FAILED && response.hasError()) {
                ErrorReason reason = response.getError().getReason();

                if (reason == ErrorReason.GPU_UNAVAILABLE || reason == ErrorReason.GPU_CAPACITY_EXCEEDED) {
                    log.warn("GPU synthesis degraded (reason={}, request={}), falling back to CPU",
                            reason, request.getRequestId());
                    return Optional.empty();
                }

                if (reason == ErrorReason.MODEL_NOT_READY && response.getError().getRetryable()
                        && attempt < maxAttempts - 1) {
                    long delay = backoff(backoffBaseMs, attempt);
                    log.debug("GPU MODEL_NOT_READY (request={}), retry {}/{} after {}ms",
                            request.getRequestId(), attempt + 1, maxAttempts, delay);
                    sleep(delay);
                    continue;
                }

                // Terminal failure
                log.warn("GPU synthesis terminal failure (reason={}, request={})",
                        reason, request.getRequestId());
                return Optional.of(new SynthesisResult.Error(
                        "GPU execution failed: " + response.getError().getMessage(), latencyMs));
            }

            return Optional.of(new SynthesisResult.Error(
                    "GPU execution returned unexpected state: " + response.getState(), latencyMs));
        }

        return Optional.empty();
    }

    private Optional<SynthesisResult> handleSuccess(
            ExecutionResponse response, long latencyMs, String requestId) {
        try {
            byte[] resultBytes = response.getResult().toByteArray();
            OpenAiChatResponse parsed = objectMapper.readValue(resultBytes, OpenAiChatResponse.class);

            String text = "";
            if (parsed.choices() != null && !parsed.choices().isEmpty()
                    && parsed.choices().get(0).message() != null) {
                text = parsed.choices().get(0).message().content();
            }

            int promptTokens = 0;
            int completionTokens = 0;
            if (parsed.usage() != null) {
                promptTokens = parsed.usage().prompt_tokens();
                completionTokens = parsed.usage().completion_tokens();
            }

            return Optional.of(new SynthesisResult.Ok(text, promptTokens, completionTokens, latencyMs));
        } catch (Exception e) {
            log.warn("GPU synthesis: response parse error (request={}): {}", requestId, e.getMessage());
            return Optional.of(new SynthesisResult.Error(
                    "GPU response parse error: " + e.getMessage(), latencyMs));
        }
    }

    private byte[] buildPayload(PromptBuilder.PromptInput input) throws Exception {
        String userMessage = "Context:\n" + input.context() + "\n\nQuestion: " + input.query();
        OpenAiChatRequest chatRequest = new OpenAiChatRequest(
                synthProps.model(),
                List.of(
                        new OpenAiChatRequest.Message("system", input.systemPrompt()),
                        new OpenAiChatRequest.Message("user", userMessage)
                ),
                synthProps.temperature(),
                synthProps.maxTokens()
        );
        return objectMapper.writeValueAsBytes(chatRequest);
    }

    private long backoff(int baseMs, int attempt) {
        return (long) (baseMs * Math.pow(2, attempt)) + JITTER.nextInt(100);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── Internal OpenAI-compat JSON structures ───────────────────────────────

    private record OpenAiChatRequest(
            String model,
            List<Message> messages,
            double temperature,
            int max_tokens
    ) {
        record Message(String role, String content) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiChatResponse(
            List<Choice> choices,
            Usage usage
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Choice(Message message) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Message(String content) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Usage(int prompt_tokens, int completion_tokens) {}
    }
}
