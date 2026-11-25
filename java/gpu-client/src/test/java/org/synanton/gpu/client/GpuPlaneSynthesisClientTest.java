package org.synanton.gpu.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.ExecutionResponse;
import org.synanton.gpu.v1.ExecutionState;
import org.synanton.gpu.v1.GPUExecutionServiceGrpc;
import org.synanton.gpu.v1.Operation;
import org.synanton.gpu.v1.Provider;
import org.synanton.llm.CompletionRequest;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GpuPlaneSynthesisClientTest {
    private Server server;
    private String serverName;

    @AfterEach
    void stop() throws Exception {
        if (server != null) server.shutdownNow().awaitTermination();
    }

    @Test
    void sendsSynthesizeThroughGatewayAndParsesCompletion() throws Exception {
        AtomicReference<ExecutionRequest> seen = new AtomicReference<>();
        start(request -> {
            seen.set(request);
            String result = "{\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}";
            return response("r1", result.getBytes(StandardCharsets.UTF_8));
        });

        try (GpuPlaneSynthesisClient client = client()) {
            var response = client.complete(new CompletionRequest(
                "synanton-qwen3-4b-synthesis", "system", "user", 0.0, 16), "demo");
            assertThat(response.text()).isEqualTo("OK");
            assertThat(response.promptTokens()).isEqualTo(7);
            assertThat(response.completionTokens()).isEqualTo(2);
            assertThat(response.finishReason()).isEqualTo("stop");
            assertThat(response.requestId()).isEqualTo("r1");
            assertThat(response.executionId()).isEqualTo("exec-r1");
            assertThat(seen.get().getOperation()).isEqualTo(Operation.SYNTHESIZE);
            assertThat(seen.get().getProvider()).isEqualTo(Provider.LOCAL);
            assertThat(seen.get().getTenantId()).isEqualTo("demo");
            assertThat(seen.get().getModel()).isEqualTo("synanton-qwen3-4b-synthesis");
            assertThat(seen.get().getPayload().toStringUtf8()).contains("\"messages\"");
        }
    }

    @Test
    void rejectsMissingFinishReason() throws Exception {
        start(request -> response("r3", "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"
            .getBytes(StandardCharsets.UTF_8)));
        try (GpuPlaneSynthesisClient client = client()) {
            assertThatThrownBy(() -> client.complete(new CompletionRequest(
                "synanton-qwen3-4b-synthesis", "system", "user", 0.0, 16), "demo"))
                .isInstanceOf(GpuPlaneException.class)
                .hasMessageContaining("finish_reason");
        }
    }

    @Test
    void rejectsMissingGatewayIdentifiers() throws Exception {
        start(request -> ExecutionResponse.newBuilder()
            .setRequestId("")
            .setExecutionId("")
            .setState(ExecutionState.SUCCESS)
            .setResult(ByteString.copyFrom("{\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}"
                .getBytes(StandardCharsets.UTF_8)))
            .build());
        try (GpuPlaneSynthesisClient client = client()) {
            assertThatThrownBy(() -> client.complete(new CompletionRequest(
                "synanton-qwen3-4b-synthesis", "system", "user", 0.0, 16), "demo"))
                .isInstanceOf(GpuPlaneException.class)
                .hasMessageContaining("requestId");
        }
    }

    @Test
    void rejectsMalformedGatewayResult() throws Exception {
        start(request -> response("r2", "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8)));
        try (GpuPlaneSynthesisClient client = client()) {
            assertThatThrownBy(() -> client.complete(new CompletionRequest(
                "synanton-qwen3-4b-synthesis", "system", "user", 0.0, 16), "demo"))
                .isInstanceOf(GpuPlaneException.class)
                .hasMessageContaining("no message content");
        }
    }

    private GpuPlaneSynthesisClient client() {
        GpuPlaneClientProperties props = new GpuPlaneClientProperties();
        props.setDefaultTenant("demo");
        props.getTls().setEnabled(false);
        return new GpuPlaneSynthesisClient(props, new ObjectMapper(),
            InProcessChannelBuilder.forName(serverName).directExecutor().build());
    }

    private interface Handler { ExecutionResponse apply(ExecutionRequest request); }

    private void start(Handler handler) throws Exception {
        serverName = "synthesis-" + System.nanoTime();
        server = InProcessServerBuilder.forName(serverName).directExecutor()
            .addService(new GPUExecutionServiceGrpc.GPUExecutionServiceImplBase() {
                @Override public void execute(ExecutionRequest request,
                                               StreamObserver<ExecutionResponse> observer) {
                    observer.onNext(handler.apply(request));
                    observer.onCompleted();
                }
            }).build().start();
    }

    private static ExecutionResponse response(String requestId, byte[] result) {
        return ExecutionResponse.newBuilder()
            .setRequestId(requestId)
            .setExecutionId("exec-" + requestId)
            .setState(ExecutionState.SUCCESS)
            .setResult(ByteString.copyFrom(result))
            .build();
    }
}
