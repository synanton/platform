package org.synanton.gateway.gpu;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.synanton.gateway.config.GatewayProperties;
import org.synanton.gateway.synthesis.PromptBuilder;
import org.synanton.gateway.synthesis.SynthesisResult;
import org.synanton.gpu.v1.*;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GpuSynthesisAdapterTest {

    @Mock GpuExecutionClient client;

    private GpuSynthesisAdapter adapter;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PromptBuilder.PromptInput INPUT =
            new PromptBuilder.PromptInput("You are an assistant.", "What is Synanton?", "Context: ...");

    @BeforeEach
    void setUp() {
        GpuExecutionClientProperties props = new GpuExecutionClientProperties();
        props.setEnabled(true);
        props.setModelVersion("1.0");
        props.getRetry().setMaxAttempts(3);
        props.getRetry().setBackoffBaseMs(1); // fast backoff in tests

        GatewayProperties.Synthesis synthProps = new GatewayProperties.Synthesis(
                true, 10, 3000, 8000, 0.3, 150, "llama-3.1-8b-instruct", "http://vllm:8000/v1");

        adapter = new GpuSynthesisAdapter(client, props, synthProps, MAPPER);
    }

    @Test
    void synthesise_gpuSuccess_returnsOkResult() throws Exception {
        String responseJson = """
                {"choices":[{"message":{"content":"Synanton is a platform."}}],
                 "usage":{"prompt_tokens":20,"completion_tokens":5}}
                """;
        ExecutionResponse response = ExecutionResponse.newBuilder()
                .setRequestId("req-1")
                .setExecutionId("exec-1")
                .setState(ExecutionState.SUCCESS)
                .setResult(com.google.protobuf.ByteString.copyFromUtf8(responseJson))
                .build();
        when(client.execute(any())).thenReturn(response);

        Optional<SynthesisResult> result = adapter.synthesise(INPUT, "tenant-1", Map.of());

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(SynthesisResult.Ok.class);
        SynthesisResult.Ok ok = (SynthesisResult.Ok) result.get();
        assertThat(ok.answer()).isEqualTo("Synanton is a platform.");
        assertThat(ok.promptTokens()).isEqualTo(20);
        assertThat(ok.completionTokens()).isEqualTo(5);
    }

    @Test
    void synthesise_gpuUnavailable_returnsEmpty() {
        ExecutionResponse response = ExecutionResponse.newBuilder()
                .setRequestId("req-2")
                .setExecutionId("exec-2")
                .setState(ExecutionState.FAILED)
                .setError(ErrorInfo.newBuilder()
                        .setReason(ErrorReason.GPU_UNAVAILABLE)
                        .setMessage("no GPU nodes available")
                        .setRetryable(false)
                        .build())
                .build();
        when(client.execute(any())).thenReturn(response);

        Optional<SynthesisResult> result = adapter.synthesise(INPUT, "tenant-1", Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    void synthesise_gpuCapacityExceeded_returnsEmpty() {
        ExecutionResponse response = ExecutionResponse.newBuilder()
                .setRequestId("req-3")
                .setExecutionId("exec-3")
                .setState(ExecutionState.FAILED)
                .setError(ErrorInfo.newBuilder()
                        .setReason(ErrorReason.GPU_CAPACITY_EXCEEDED)
                        .setMessage("cluster at max capacity")
                        .setRetryable(false)
                        .build())
                .build();
        when(client.execute(any())).thenReturn(response);

        Optional<SynthesisResult> result = adapter.synthesise(INPUT, "tenant-1", Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    void synthesise_deadlineExceeded_returnsEmpty() {
        when(client.execute(any())).thenThrow(
                new StatusRuntimeException(Status.DEADLINE_EXCEEDED));

        Optional<SynthesisResult> result = adapter.synthesise(INPUT, "tenant-1", Map.of());

        assertThat(result).isEmpty();
        verify(client, times(1)).execute(any()); // no retry on timeout
    }

    @Test
    void synthesise_grpcUnavailable_returnsEmpty() {
        when(client.execute(any())).thenThrow(
                new StatusRuntimeException(Status.UNAVAILABLE));

        Optional<SynthesisResult> result = adapter.synthesise(INPUT, "tenant-1", Map.of());

        assertThat(result).isEmpty();
        verify(client, times(1)).execute(any()); // no retry on UNAVAILABLE
    }

    @Test
    void synthesise_modelNotReady_retriesAndEventuallySucceeds() throws Exception {
        ExecutionResponse notReady = ExecutionResponse.newBuilder()
                .setRequestId("req-4")
                .setExecutionId("exec-4")
                .setState(ExecutionState.FAILED)
                .setError(ErrorInfo.newBuilder()
                        .setReason(ErrorReason.MODEL_NOT_READY)
                        .setMessage("model loading")
                        .setRetryable(true)
                        .build())
                .build();
        String responseJson = """
                {"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":5,"completion_tokens":1}}
                """;
        ExecutionResponse success = ExecutionResponse.newBuilder()
                .setRequestId("req-4")
                .setExecutionId("exec-4")
                .setState(ExecutionState.SUCCESS)
                .setResult(com.google.protobuf.ByteString.copyFromUtf8(responseJson))
                .build();
        when(client.execute(any())).thenReturn(notReady, success);

        Optional<SynthesisResult> result = adapter.synthesise(INPUT, "tenant-1", Map.of());

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(SynthesisResult.Ok.class);
        verify(client, times(2)).execute(any());
    }

    @Test
    void synthesise_terminalFailure_returnsErrorResult() {
        ExecutionResponse response = ExecutionResponse.newBuilder()
                .setRequestId("req-5")
                .setExecutionId("exec-5")
                .setState(ExecutionState.FAILED)
                .setError(ErrorInfo.newBuilder()
                        .setReason(ErrorReason.EXECUTION_FAILED)
                        .setMessage("CUDA OOM")
                        .setRetryable(false)
                        .build())
                .build();
        when(client.execute(any())).thenReturn(response);

        Optional<SynthesisResult> result = adapter.synthesise(INPUT, "tenant-1", Map.of());

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(SynthesisResult.Error.class);
        SynthesisResult.Error err = (SynthesisResult.Error) result.get();
        assertThat(err.message()).contains("CUDA OOM");
    }

    @Test
    void synthesise_traceContextPropagated() {
        when(client.execute(any())).thenAnswer(inv -> {
            ExecutionRequest req = inv.getArgument(0);
            assertThat(req.getTraceContextMap()).containsEntry("traceparent", "00-abc-01");
            return ExecutionResponse.newBuilder()
                    .setRequestId(req.getRequestId())
                    .setExecutionId("exec-trace")
                    .setState(ExecutionState.SUCCESS)
                    .setResult(com.google.protobuf.ByteString.copyFromUtf8(
                            "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"))
                    .build();
        });

        Map<String, String> trace = Map.of("traceparent", "00-abc-01");
        Optional<SynthesisResult> result = adapter.synthesise(INPUT, "tenant-1", trace);

        assertThat(result).isPresent();
    }
}
