package org.synanton.gpu.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.GPUExecutionServiceGrpc;
import org.synanton.gpu.v1.GetModelsRequest;
import org.synanton.gpu.v1.ModelInfo;
import org.synanton.gpu.v1.Operation;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.TenantAwareLlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
 * e.g. {@code bge-small-384d}. The Gateway maps it to a provider model and never
 * exposes the provider ID. Input texts are never logged.
 *
 * <p>Batches larger than {@code gpu-plane.max-batch-size} are split into sequential
 * Execute calls. The gateway contract does not advertise a batch size yet, so the cap is
 * client configuration.
 */
public class GpuPlaneEmbedClient implements TenantAwareLlmClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GpuPlaneEmbedClient.class);

    private final GpuPlaneClientProperties props;
    private final GpuEmbedCodec codec;
    private final ManagedChannel ownedChannel;
    private final GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stub;
    private final GpuPlaneExecutor executor;

    /** Builds (and owns) the channel. Invalid mTLS material fails here, at startup. */
    public GpuPlaneEmbedClient(GpuPlaneClientProperties props, ObjectMapper mapper) {
        this(props, mapper, (Supplier<String>) null);
    }

    /**
     * Builds (and owns) the channel and attaches an execution JWT when the provider
     * returns a non-blank token. T-K8S-6a supplies the real signer.
     */
    public GpuPlaneEmbedClient(
            GpuPlaneClientProperties props,
            ObjectMapper mapper,
            Supplier<String> jwtProvider) {
        this(props, mapper, GpuPlaneChannels.build(props.getEndpoint(), props.getTls(), "gpu-plane.tls"), true, jwtProvider);
    }

    /** Uses a caller-owned channel (tests, shared channels). */
    public GpuPlaneEmbedClient(GpuPlaneClientProperties props, ObjectMapper mapper, Channel channel) {
        this(props, mapper, channel, false, null);
    }

    private GpuPlaneEmbedClient(
            GpuPlaneClientProperties props,
            ObjectMapper mapper,
            Channel channel,
            boolean owned,
            Supplier<String> jwtProvider) {
        this.props = props;
        this.codec = new GpuEmbedCodec(mapper);
        this.ownedChannel = owned ? (ManagedChannel) channel : null;
        Channel callChannel = jwtProvider == null
                ? channel
                : ClientInterceptors.intercept(channel, new ExecutionJwtInterceptor(jwtProvider));
        this.stub = GPUExecutionServiceGrpc.newBlockingStub(callChannel);
        this.executor = new GpuPlaneExecutor(props, stub);
        log.info("GPU plane embed client → {} (mTLS={}, JWT={}, fail-closed, max {} req/min, batch {})",
                props.getEndpoint(),
                props.getTls().isEnabled(),
                jwtProvider != null,
                props.getMaxRequestsPerMinute() > 0 ? props.getMaxRequestsPerMinute() : "∞",
                Math.max(1, props.getMaxBatchSize()));
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

        int maxBatch = Math.max(1, props.getMaxBatchSize());
        if (request.inputs().size() <= maxBatch) {
            return embedBatch(request.model(), tenantId, request.inputs());
        }

        List<float[]> embeddings = new ArrayList<>(request.inputs().size());
        int inputChars = 0;
        int inputTokens = 0;
        long durationMs = 0;
        for (int start = 0; start < request.inputs().size(); start += maxBatch) {
            int end = Math.min(start + maxBatch, request.inputs().size());
            EmbedResponse batch = embedBatch(request.model(), tenantId, request.inputs().subList(start, end));
            embeddings.addAll(batch.embeddings());
            inputChars += batch.inputChars();
            inputTokens += batch.inputTokens();
            durationMs += batch.durationMs();
        }
        return new EmbedResponse(embeddings, inputChars, 0, durationMs, inputTokens, 0);
    }

    private EmbedResponse embedBatch(String model, String tenantId, List<String> inputs) {
        ExecutionRequest exec = ExecutionRequest.newBuilder()
                .setRequestId("embed-" + UUID.randomUUID())
                .setTenantId(tenantId)
                .setModel(model)
                .setModelVersion(props.getModelVersion())
                .setOperation(Operation.EMBED)
                .setPayload(ByteString.copyFrom(codec.payload(model, inputs)))
                .build();
        int inputChars = inputs.stream().mapToInt(String::length).sum();

        GpuPlaneExecutor.Outcome outcome = executor.execute(exec);
        GpuEmbedCodec.Result r = codec.parse(outcome.response().getResult().toByteArray(), inputs.size());
        return new EmbedResponse(r.embeddings(), inputChars, 0, outcome.latencyMs(), r.promptTokens(), 0);
    }

    /**
     * Reads the gateway's EMBED model catalog. Used by synquest's startup capability
     * assertion to confirm the configured model id and embedding dimension. The provider
     * is left unspecified so the check works for both local and external catalogs.
     */
    public List<ModelInfo> models(String tenantId) {
        GetModelsRequest request = GetModelsRequest.newBuilder()
                .setOperation(Operation.EMBED)
                .setTenantId(tenantId == null ? "" : tenantId)
                .build();
        try {
            return stub.withDeadlineAfter(props.getTimeoutMs(), TimeUnit.MILLISECONDS)
                    .getModels(request)
                    .getModelsList();
        } catch (StatusRuntimeException e) {
            String code = GpuErrorCodes.canonicalCode(e);
            throw new GpuPlaneException(
                    code == null ? "gpu_models_unavailable" : code,
                    "GPU plane GetModels failed (" + e.getStatus().getCode() + ")",
                    e);
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
}
