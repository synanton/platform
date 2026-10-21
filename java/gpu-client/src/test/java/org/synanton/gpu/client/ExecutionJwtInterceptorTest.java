package org.synanton.gpu.client;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.synanton.gpu.v1.GPUExecutionServiceGrpc;
import org.synanton.gpu.v1.GetModelsRequest;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionJwtInterceptorTest {

    private Server server;
    private ManagedChannel base;

    @AfterEach
    void stop() {
        if (base != null) {
            base.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    private GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stubWith(
            ServerInterceptor capture,
            java.util.function.Supplier<String> tokenProvider) throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.intercept(
                        new GPUExecutionServiceGrpc.GPUExecutionServiceImplBase() {}, capture))
                .build()
                .start();
        base = InProcessChannelBuilder.forName(name).directExecutor().build();
        Channel channel = ClientInterceptors.intercept(base, new ExecutionJwtInterceptor(tokenProvider));
        return GPUExecutionServiceGrpc.newBlockingStub(channel);
    }

    private static ServerInterceptor capture(AtomicReference<String> seen) {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                seen.set(headers.get(ExecutionJwtInterceptor.AUTHORIZATION));
                return next.startCall(call, headers);
            }
        };
    }

    private static void invokeUnimplemented(
            GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stub) {
        try {
            stub.getModels(GetModelsRequest.getDefaultInstance());
        } catch (StatusRuntimeException ignored) {
            // The base service does not implement GetModels; the client interceptor still ran.
        }
    }

    @Test
    void attachesBearerTokenWhenTheProviderReturnsOne() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        invokeUnimplemented(stubWith(capture(seen), () -> "token-123"));

        assertThat(seen.get()).isEqualTo("Bearer token-123");
    }

    @Test
    void isANoopWhenTheProviderReturnsNothing() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        invokeUnimplemented(stubWith(capture(seen), () -> null));

        assertThat(seen.get()).isNull();
    }
}
