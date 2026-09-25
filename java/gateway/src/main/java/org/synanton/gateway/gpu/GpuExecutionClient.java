package org.synanton.gateway.gpu;

import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.gpu.v1.*;

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

    // Canonical error codes (Plan §16.1): delegates to the shared gpu-client GpuErrorCodes,
    // so gateway, synquest and synflux classify denials identically.
    public static String canonicalCode(io.grpc.StatusRuntimeException e) {
        return org.synanton.gpu.client.GpuErrorCodes.canonicalCode(e);
    }

    public static String canonicalCode(ExecutionResponse response) {
        return org.synanton.gpu.client.GpuErrorCodes.canonicalCode(response);
    }

    public static boolean isRetryableDenial(io.grpc.StatusRuntimeException e) {
        return org.synanton.gpu.client.GpuErrorCodes.isRetryableDenial(e);
    }

    static final io.grpc.Metadata.Key<String> ERROR_CODE_KEY = org.synanton.gpu.client.GpuErrorCodes.ERROR_CODE_KEY;

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
        GpuExecutionClientProperties.Tls tls = properties.getTls();
        var shared = new org.synanton.gpu.client.GpuPlaneClientProperties.Tls();
        shared.setEnabled(tls.isEnabled());
        shared.setCaPath(tls.getCaPath());
        shared.setCertPath(tls.getCertPath());
        shared.setKeyPath(tls.getKeyPath());
        shared.setAuthority(tls.getAuthority());
        return org.synanton.gpu.client.GpuPlaneChannels.build(properties.getEndpoint(), shared, "gateway.gpu.tls");
    }
}
