package org.synanton.gpu.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.GPUExecutionServiceGrpc;
import org.synanton.gpu.v1.Operation;
import org.synanton.gpu.v1.Provider;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.TenantAwareLlmClient;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Fail-closed LLM completion client through the GPU Gateway's {@code SYNTHESIZE} operation.
 * It is the supported platform path for semantic extraction; it never calls vLLM/TEI directly.
 */
public class GpuPlaneSynthesisClient implements TenantAwareLlmClient, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GpuPlaneSynthesisClient.class);

    private final GpuPlaneClientProperties props;
    private final ObjectMapper mapper;
    private final ManagedChannel ownedChannel;
    private final GPUExecutionServiceGrpc.GPUExecutionServiceBlockingStub stub;
    private final GpuPlaneExecutor executor;

    public GpuPlaneSynthesisClient(GpuPlaneClientProperties props, ObjectMapper mapper) {
        this(props, mapper, GpuPlaneChannels.build(props.getEndpoint(), props.getTls(), "gpu-plane.tls"),
            true);
    }

    public GpuPlaneSynthesisClient(GpuPlaneClientProperties props, ObjectMapper mapper, Channel channel) {
        this(props, mapper, channel, false);
    }

    private GpuPlaneSynthesisClient(GpuPlaneClientProperties props, ObjectMapper mapper, Channel channel,
                                    boolean owned) {
        this.props = props;
        this.mapper = mapper;
        this.ownedChannel = owned ? (ManagedChannel) channel : null;
        this.stub = GPUExecutionServiceGrpc.newBlockingStub(channel);
        this.executor = new GpuPlaneExecutor(props, stub);
        log.info("GPU plane synthesis client → {} (mTLS={}, fail-closed)",
            props.getEndpoint(), props.getTls().isEnabled());
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        String tenant = props.getDefaultTenant();
        if (tenant == null || tenant.isBlank()) {
            throw new GpuPlaneException("tenant_required",
                "GPU plane SYNTHESIZE needs a tenant: call complete(request, tenantId) or set gpu-plane.default-tenant");
        }
        return complete(request, tenant);
    }

    /** Completion with an explicit tenant assertion; required for evaluation/ingestion calls. */
    @Override
    public CompletionResponse complete(CompletionRequest request, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new GpuPlaneException("tenant_required", "GPU plane SYNTHESIZE needs a tenant");
        }
        if (request == null || request.model() == null || request.model().isBlank()) {
            throw new GpuPlaneException("invalid_request", "GPU plane SYNTHESIZE needs a logical model ID");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        messages.addObject().put("role", "user").put("content", request.userMessage());
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxTokens());
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new GpuPlaneException("invalid_request", "cannot serialise SYNTHESIZE payload", e);
        }
        ExecutionRequest exec = ExecutionRequest.newBuilder()
            .setRequestId("synthesize-" + UUID.randomUUID())
            .setTenantId(tenantId)
            .setModel(request.model())
            .setModelVersion(props.getModelVersion())
            .setOperation(Operation.SYNTHESIZE)
            .setProvider(Provider.LOCAL)
            .setPayload(ByteString.copyFrom(payload))
            .build();
        GpuPlaneExecutor.Outcome outcome = executor.execute(exec);
        return parse(outcome.response(), outcome.response().getResult().toByteArray());
    }

    private CompletionResponse parse(org.synanton.gpu.v1.ExecutionResponse response, byte[] result) {
        try {
            if (response.getRequestId() == null || response.getRequestId().isBlank()) {
                throw new GpuPlaneException("invalid_result", "SYNTHESIZE response missing requestId");
            }
            if (response.getExecutionId() == null || response.getExecutionId().isBlank()) {
                throw new GpuPlaneException("invalid_result", "SYNTHESIZE response missing executionId");
            }
            JsonNode root = mapper.readTree(result);
            JsonNode choice = root == null ? null : root.path("choices").path(0);
            JsonNode content = choice == null ? null : choice.path("message").path("content");
            JsonNode finish = choice == null ? null : choice.path("finish_reason");
            if (content == null || !content.isTextual()) {
                throw new GpuPlaneException("invalid_result", "SYNTHESIZE result has no message content");
            }
            if (finish == null || !finish.isTextual() || finish.asText().isBlank()) {
                throw new GpuPlaneException("invalid_result", "SYNTHESIZE result missing finish_reason");
            }
            return new CompletionResponse(content.asText(),
                root.path("usage").path("prompt_tokens").asInt(0),
                root.path("usage").path("completion_tokens").asInt(0),
                finish.asText(), response.getRequestId(), response.getExecutionId());
        } catch (GpuPlaneException e) {
            throw e;
        } catch (Exception e) {
            throw new GpuPlaneException("invalid_result", "SYNTHESIZE result is not valid JSON", e);
        }
    }

    @Override
    public EmbedResponse embed(EmbedRequest request) {
        throw new GpuPlaneException("capability_not_supported", "GPU synthesis client serves SYNTHESIZE only");
    }

    @Override
    public EmbedResponse embed(EmbedRequest request, String tenantId) {
        throw new GpuPlaneException("capability_not_supported", "GPU synthesis client serves SYNTHESIZE only");
    }

    @Override
    public void close() {
        if (ownedChannel != null) {
            ownedChannel.shutdown();
        }
    }
}
