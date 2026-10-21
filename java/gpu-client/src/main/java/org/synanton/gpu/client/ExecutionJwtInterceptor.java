package org.synanton.gpu.client;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Attaches the GPU gateway execution JWT as {@code authorization: Bearer <token>}.
 *
 * <p>The signer is deployment-owned (T-K8S-6a): callers pass a {@link Supplier} that
 * returns the short-lived token. A null/blank token is a no-op, preserving the current
 * mTLS-only deployments until the signer is wired.
 */
public final class ExecutionJwtInterceptor implements ClientInterceptor {

    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final Supplier<String> tokenProvider;

    public ExecutionJwtInterceptor(Supplier<String> tokenProvider) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String token = tokenProvider.get();
                if (token != null && !token.isBlank()) {
                    headers.put(AUTHORIZATION, "Bearer " + token);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
