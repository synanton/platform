package org.synanton.gpu.gateway.dispatch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.gpu.gateway.config.GpuGatewayProperties;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.Operation;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DirectDispatcherTest {

    private HttpServer mockVllm;
    private DirectDispatcher dispatcher;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        mockVllm = HttpServer.create(new InetSocketAddress(0), 0);
        port = mockVllm.getAddress().getPort();

        GpuGatewayProperties props = new GpuGatewayProperties();
        props.getDispatch().setVllmEndpoint("http://localhost:" + port);
        props.getDispatch().setTimeoutMs(5000);
        dispatcher = new DirectDispatcher(props);
    }

    @AfterEach
    void tearDown() {
        if (mockVllm != null) mockVllm.stop(0);
    }

    @Test
    void dispatch_synthesize_success() {
        String responseBody = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";
        mockVllm.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });
        mockVllm.start();

        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setRequestId("req-1").setTenantId("t1").setModel("llama3").setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(com.google.protobuf.ByteString.copyFromUtf8("{\"messages\":[]}"))
                .build();

        DispatchResult result = dispatcher.dispatch(request, "exec-1");

        assertThat(result.success()).isTrue();
        assertThat(new String(result.result(), StandardCharsets.UTF_8)).contains("hello");
    }

    @Test
    void dispatch_vllmReturnsError_resultIsFailed() {
        mockVllm.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = "{\"error\":\"overloaded\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });
        mockVllm.start();

        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setRequestId("req-2").setTenantId("t1").setModel("llama3").setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .setPayload(com.google.protobuf.ByteString.copyFromUtf8("{\"messages\":[]}"))
                .build();

        DispatchResult result = dispatcher.dispatch(request, "exec-2");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("503");
    }

    @Test
    void dispatch_emptyPayload_returnsFailed() {
        mockVllm.start();
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setRequestId("req-3").setTenantId("t1").setModel("llama3").setModelVersion("1.0")
                .setOperation(Operation.SYNTHESIZE)
                .build();

        DispatchResult result = dispatcher.dispatch(request, "exec-3");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).containsIgnoringCase("empty");
    }
}
