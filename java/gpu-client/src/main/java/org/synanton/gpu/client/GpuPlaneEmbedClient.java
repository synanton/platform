package org.synanton.gpu.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.ExecutionResponse;
import org.synanton.gpu.v1.ExecutionState;
import org.synanton.gpu.v1.GPUExecutionServiceGrpc;
import org.synanton.gpu.v1.Operation;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.TenantAwareLlmClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Fail-closed embedding client for the GPU plane ({@code Operation.EMBED} over gRPC
 * {@code synanton.gpu.v1}, with mTLS).
 *
 * <p>Unlike the gateway's degrade-to-CPU {@code GpuEmbeddingAdapter}, this client never
 * returns an empty or partial result. Every failure throws {@link GpuPlaneException} with the
 * canonical error code, so a caller can't mistake "the GPU plane refused" for "no vectors".
 * Only transient outcomes are retried (see {@link GpuErrorCodes#isTransient}): capacity
 * denials, transport {@code UNAVAILABLE}, and a retryable {@code MODEL_NOT_READY}. Policy
 * denials (circuit_open, routing_disabled, budget_exceeded, tenant_not_allowed,
 * sensitive_model_external_blocked, …) fail on the first attempt.
 *
 * <p>{@code EmbedRequest.model} must be a <em>logical</em> model ID from the Gateway catalog,
 * e.g. {@code synanton-free-embedding}. The Gateway maps it to a provider model and never
 * exposes the provider ID. Input texts are never logged.
 */
public class GpuPlaneEmbedClient implements TenantAwareLlmClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GpuPlaneEmbedClient.class);

    private final GpuPlaneClientProperties props;
    private final GpuEmbedCodec codec;
    private final ManagedChannel ownedChannel;
    private final GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stub;

    /** Builds (and owns) the channel. Invalid mTLS material fails here, at startup. */
    public GpuPlaneEmbedClient(GpuPlaneClientProperties props, ObjectMapper mapper) {
        this(props, mapper, GpuPlaneChannels.build(props.getEndpoint(), props.getTls(), "gpu-plane.tls"), true);
    }

    /** Uses a caller-owned channel (tests, shared channels). */
    public GpuPlaneEmbedClient(GpuPlaneClientProperties props, ObjectMapper mapper, Channel channel) {
        this(props, mapper, channel, false);
    }

    private GpuPlaneEmbedClient(GpuPlaneClientProperties props, ObjectMapper mapper, Channel channel, boolean owned) {
        this.props = props;
        this.codec = new GpuEmbedCodec(mapper);
        this.ownedChannel = owned ? (ManagedChannel) channel : null;
        this.stub = GPUExecutionServiceGrpc.newBlockingStub(channel);
        log.info("GPU plane embed client → {} (mTLS={}, fail-closed)", props.getEndpoint(), props.getTls().isEnabled());
    }

    @Override
    public EmbedResponse embed(EmbedRequest request) {
        String tenant = props.getDefaultTenant();
        if (tenant == null || tenant.isBlank()) {
            throw new GpuPlaneException("tenant_required",
                    "GPU plane EMBED needs a tenant: call embed(request, tenantId) or set gpu-plane.default-tenant");
        }
        return embed(request, tenant);
    }

    @Override
    public EmbedResponse embed(EmbedRequest request, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new GpuPlaneException("tenant_required", "GPU plane EMBED needs a tenant");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new GpuPlaneException("invalid_request", "GPU plane EMBED needs a logical model ID");
        }
        if (request.inputs() == null || request.inputs().isEmpty()) {
            throw new GpuPlaneException("invalid_request", "GPU plane EMBED needs at least one input");
        }
        ExecutionRequest exec = ExecutionRequest.newBuilder()
                .setRequestId("embed-" + UUID.randomUUID())
                .setTenantId(tenantId)
                .setModel(request.model())
                .setModelVersion(props.getModelVersion())
                .setOperation(Operation.EMBED)
                .setPayload(ByteString.copyFrom(codec.payload(request.model(), request.inputs())))
                .build();
        int inputChars = request.inputs().stream().mapToInt(String::length).sum();

        int maxAttempts = Math.max(1, props.getRetry().getMaxAttempts());
        for (int attempt = 1; ; attempt++) {
            long start = System.currentTimeMillis();
            ExecutionResponse response;
            try {
                response = stub.withDeadlineAfter(props.getTimeoutMs(), TimeUnit.MILLISECONDS).execute(exec);
            } catch (StatusRuntimeException e) {
                String code = GpuErrorCodes.canonicalCode(e);
                if (GpuErrorCodes.isTransient(e) && attempt < maxAttempts) {
                    retryLog(exec, code, attempt, maxAttempts);
                    sleep(backoff(attempt));
                    continue;
                }
                log.warn("GPU plane EMBED denied: code={} grpc={} request={} tenant={} model={}",
                        code, e.getStatus().getCode(), exec.getRequestId(), tenantId, request.model());
                throw new GpuPlaneException(code, "GPU plane EMBED failed (" + e.getStatus().getCode() + ")", e);
            }
            long latencyMs = System.currentTimeMillis() - start;

            if (response.getState() == ExecutionState.SUCCESS) {
                GpuEmbedCodec.Result r = codec.parse(response.getResult().toByteArray(), request.inputs().size());
                return new EmbedResponse(r.embeddings(), inputChars, 0, latencyMs, r.promptTokens(), 0);
            }
            String code = GpuErrorCodes.canonicalCode(response);
            if (code == null) {
                code = "state_" + response.getState().name().toLowerCase();
            }
            if (GpuErrorCodes.isTransient(response) && attempt < maxAttempts) {
                retryLog(exec, code, attempt, maxAttempts);
                sleep(backoff(attempt));
                continue;
            }
            log.warn("GPU plane EMBED failed: state={} code={} reason={} upstream_request_id={} request={} tenant={} model={}",
                    response.getState(), code, response.getError().getReason(), response.getUpstreamRequestId(),
                    exec.getRequestId(), tenantId, request.model());
            throw new GpuPlaneException(code, "GPU plane EMBED " + response.getState());
        }
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        throw new GpuPlaneException("capability_not_supported", "GpuPlaneEmbedClient serves EMBED only");
    }

    @Override
    public void close() {
        if (ownedChannel != null) {
            ownedChannel.shutdown();
        }
    }

    private void retryLog(ExecutionRequest exec, String code, int attempt, int max) {
        log.info("GPU plane EMBED transient code={} request={} — retry {}/{}", code, exec.getRequestId(), attempt, max - 1);
    }

    private long backoff(int attempt) {
        return (long) (props.getRetry().getBackoffBaseMs() * Math.pow(2, attempt - 1));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new GpuPlaneException("cancelled", "interrupted while backing off");
        }
    }
}
