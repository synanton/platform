package org.synanton.gateway.gpu;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.gpu.v1.*;

import java.io.File;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

// GpuExecutionClient is the primary platform's gRPC client for synanton.gpu.v1.GPUExecutionService.
// It is only activated when gateway.gpu.enabled=true. When disabled, the gateway falls back
// to the v1.19 CPU execution path.
//
// Ownership invariants:
//   - request_id is supplied by this client (from the gateway's workflow context).
//   - execution_id is received from the Gateway; callers must not fabricate it.
//   - On Execute() timeout, callers MUST call getStatus(executionId) - do not assume failure.
@Component
public class GpuExecutionClient {

    private static final Logger log = LoggerFactory.getLogger(GpuExecutionClient.class);

    private final GpuExecutionClientProperties properties;
    private volatile ManagedChannel channel;
    private volatile GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stub;

    public GpuExecutionClient(GpuExecutionClientProperties properties) {
        this.properties = properties;
    }

    // execute dispatches a GPU workload. Blocks until completion or timeout.
    // On timeout, the caller MUST call getStatus(executionId) to reconcile the outcome.
    public ExecutionResponse execute(ExecutionRequest request) {
        ensureChannel();
        return stub.withDeadlineAfter(properties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .execute(request);
    }

    // executeEmbed dispatches an embedding workload.
    public ExecutionResponse executeEmbed(ExecutionRequest request) {
        ensureChannel();
        return stub.withDeadlineAfter(properties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .execute(request);
    }

    // executeRerank dispatches a reranking workload.
    public ExecutionResponse executeRerank(ExecutionRequest request) {
        ensureChannel();
        return stub.withDeadlineAfter(properties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .execute(request);
    }

    // executeStream dispatches a streaming SYNTHESIZE workload (Deployment Plan §10):
    // zero or more ExecutionChunk.data (one chat.completion.chunk JSON each, logical model
    // ID), then exactly one ExecutionChunk.terminal with final state/usage/error.
    // Consume the iterator fully (or cancel the deadline); a closed stream does NOT cancel
    // the GPU-side execution — reconcile with getStatus like execute().
    public Iterator<ExecutionChunk> executeStream(ExecutionRequest request) {
        ensureChannel();
        return stub.withDeadlineAfter(properties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .executeStream(request);
    }

    // Canonical error code of a pre-execution denial (Plan §16.1): the
    // x-synanton-error-code trailer, e.g. "tenant_not_allowed", "budget_exceeded".
    public static String canonicalCode(io.grpc.StatusRuntimeException e) {
        io.grpc.Metadata trailers = e.getTrailers();
        String code = trailers == null ? null : trailers.get(ERROR_CODE_KEY);
        return code != null ? code : "status_" + e.getStatus().getCode().name().toLowerCase();
    }

    // Canonical error code of a failed execution (ErrorInfo.code), or null on success.
    public static String canonicalCode(ExecutionResponse response) {
        return response.hasError() && !response.getError().getCode().isEmpty()
                ? response.getError().getCode() : null;
    }

    // Whether a pre-execution denial may succeed on retry. Only capacity-type denials are
    // transient; security/policy/validation denials (tenant_not_allowed, budget_exceeded,
    // sensitive_model_external_blocked, model_not_found, …) never succeed on retry.
    public static boolean isRetryableDenial(io.grpc.StatusRuntimeException e) {
        return switch (canonicalCode(e)) {
            case "concurrency_limit_reached", "capacity_exceeded" -> true;
            default -> e.getTrailers() == null || e.getTrailers().get(ERROR_CODE_KEY) == null; // legacy gateway: keep old behaviour
        };
    }

    static final io.grpc.Metadata.Key<String> ERROR_CODE_KEY =
            io.grpc.Metadata.Key.of("x-synanton-error-code", io.grpc.Metadata.ASCII_STRING_MARSHALLER);

    public CancelResponse cancel(CancelRequest request) {
        ensureChannel();
        return stub.cancel(request);
    }

    // getStatus is authoritative. Call this after any Execute() timeout to reconcile outcome.
    public ExecutionStatus getStatus(GetStatusRequest request) {
        ensureChannel();
        return stub.getStatus(request);
    }

    // getCapacity is advisory. A successful response does NOT reserve GPU capacity.
    public CapacityResponse getCapacity(GetCapacityRequest request) {
        ensureChannel();
        return stub.getCapacity(request);
    }

    // getModels returns available models for the given operation and provider.
    public GetModelsResponse getModels(GetModelsRequest request) {
        ensureChannel();
        return stub.getModels(request);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    private void ensureChannel() {
        if (channel == null || channel.isShutdown()) {
            synchronized (this) {
                if (channel == null || channel.isShutdown()) {
                    channel = buildChannel();
                    stub = GPUExecutionServiceGrpc.newBlockingStub(channel);
                    log.info("GPU execution client connected to {}", properties.getEndpoint());
                }
            }
        }
    }

    // Package-visible for tests. With tls.enabled the channel uses mTLS: the Gateway's CA
    // (ca-path) verifies the server, and this client presents cert-path/key-path — its CN is
    // the principal the Gateway authorizes for tenant_id (Deployment Plan §13,
    // gpu-runtime doc/GPU Plane mTLS Setup.md). Missing TLS files fail closed at connect.
    ManagedChannel buildChannel() {
        String[] parts = properties.getEndpoint().split(":", 2);
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9090;

        GpuExecutionClientProperties.Tls tls = properties.getTls();
        if (!tls.isEnabled()) {
            log.warn("GPU execution client uses PLAINTEXT gRPC to {} — only valid against a gateway "
                    + "in security.mode=insecure-plaintext (tests/loopback)", properties.getEndpoint());
            return ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        }
        for (String[] f : new String[][]{{"ca-path", tls.getCaPath()}, {"cert-path", tls.getCertPath()},
                {"key-path", tls.getKeyPath()}}) {
            if (f[1] == null || !new File(f[1]).canRead()) {
                throw new IllegalStateException("gateway.gpu.tls." + f[0] + " is not a readable file "
                        + "(mTLS is required when gateway.gpu.tls.enabled=true)");
            }
        }
        try {
            var ssl = io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                    .trustManager(new File(tls.getCaPath()))
                    .keyManager(new File(tls.getCertPath()), new File(tls.getKeyPath()))
                    .build();
            var builder = io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forAddress(host, port)
                    .sslContext(ssl);
            if (tls.getAuthority() != null && !tls.getAuthority().isBlank()) {
                builder.overrideAuthority(tls.getAuthority()); // match a server-certificate SAN
            }
            return builder.build();
        } catch (javax.net.ssl.SSLException e) {
            throw new IllegalStateException("Invalid GPU gateway TLS material", e);
        }
    }
}
