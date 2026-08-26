package org.synanton.gateway.gpu;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.gpu.v1.*;

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

    private ManagedChannel buildChannel() {
        String[] parts = properties.getEndpoint().split(":", 2);
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9090;

        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forAddress(host, port);
        if (!properties.getTls().isEnabled()) {
            builder.usePlaintext();
        }
        // Production: configure mTLS via SslContext when tls.enabled=true
        return builder.build();
    }
}
