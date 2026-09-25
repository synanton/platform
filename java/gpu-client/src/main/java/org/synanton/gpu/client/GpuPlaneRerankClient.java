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
import org.synanton.llm.RerankClient;
import org.synanton.llm.RerankRequest;
import org.synanton.llm.RerankResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fail-closed cross-encoder reranking through the GPU plane ({@code Operation.RERANK} over gRPC
 * {@code synanton.gpu.v1} with mTLS). On GPU-5 it reaches vLLM {@code /v1/rerank}, e.g.
 * {@code synanton-qwen3-reranker-0.6b} on node2.
 *
 * <ul>
 *   <li>Payload: {@code {"model", "query", "documents": [...]}} with the {@link RerankPromptFormat}
 *       applied. Results are returned for <em>every</em> passage, sorted by descending score. The
 *       caller cuts to its own top N.</li>
 *   <li>Every failure throws {@link GpuPlaneException} with the canonical code: denials, wrong
 *       result count, out-of-range or duplicate indices, unparsable bodies. It never returns an
 *       empty or partial ranking, so a caller can't mistake "reranker down" for "reranked".</li>
 *   <li>The returned {@code passage} is the caller's original text, never the templated one.</li>
 * </ul>
 */
public class GpuPlaneRerankClient implements RerankClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GpuPlaneRerankClient.class);

    private final GpuPlaneClientProperties props;
    private final ObjectMapper mapper;
    private final RerankPromptFormat format;
    private final String instruction;
    private final ManagedChannel ownedChannel;
    private final GpuPlaneExecutor executor;

    public GpuPlaneRerankClient(GpuPlaneClientProperties props, ObjectMapper mapper, RerankPromptFormat format, String instruction) {
        this(props, mapper, format, instruction,
                GpuPlaneChannels.build(props.getEndpoint(), props.getTls(), "gpu-plane.tls"), true);
    }

    public GpuPlaneRerankClient(GpuPlaneClientProperties props, ObjectMapper mapper, RerankPromptFormat format,
                                String instruction, Channel channel) {
        this(props, mapper, format, instruction, channel, false);
    }

    private GpuPlaneRerankClient(GpuPlaneClientProperties props, ObjectMapper mapper, RerankPromptFormat format,
                                 String instruction, Channel channel, boolean owned) {
        this.props = props;
        this.mapper = mapper;
        this.format = format == null ? RerankPromptFormat.PLAIN : format;
        this.instruction = instruction;
        this.ownedChannel = owned ? (ManagedChannel) channel : null;
        this.executor = new GpuPlaneExecutor(props, GPUExecutionServiceGrpc.newBlockingStub(channel));
        log.info("GPU plane rerank client → {} (mTLS={}, fail-closed, prompt format {})",
                props.getEndpoint(), props.getTls().isEnabled(), this.format);
    }

    @Override
    public RerankResponse rerank(RerankRequest request) {
        String tenant = props.getDefaultTenant();
        if (tenant == null || tenant.isBlank()) {
            throw new GpuPlaneException("tenant_required", "GPU plane RERANK needs a tenant: call rerank(request, tenantId)");
        }
        return rerank(request, tenant);
    }

    public RerankResponse rerank(RerankRequest request, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new GpuPlaneException("tenant_required", "GPU plane RERANK needs a tenant");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new GpuPlaneException("invalid_request", "GPU plane RERANK needs a logical model ID");
        }
        List<String> passages = request.passages() == null ? List.of() : request.passages();
        if (passages.isEmpty()) {
            return new RerankResponse(List.of(), 0, 0, 0, 0, 0);
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.model());
        body.put("query", format.query(request.query(), instruction));
        ArrayNode docs = body.putArray("documents");
        passages.forEach(p -> docs.add(format.passage(p)));
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new GpuPlaneException("invalid_request", "cannot serialise RERANK payload", e);
        }
        ExecutionRequest exec = ExecutionRequest.newBuilder()
                .setRequestId("rerank-" + UUID.randomUUID())
                .setTenantId(tenantId)
                .setModel(request.model())
                .setModelVersion(props.getModelVersion())
                .setOperation(Operation.RERANK)
                .setPayload(ByteString.copyFrom(payload))
                .build();

        GpuPlaneExecutor.Outcome outcome = executor.execute(exec);
        return parse(outcome.response().getResult().toByteArray(), passages, outcome.latencyMs(),
                passages.stream().mapToInt(String::length).sum());
    }

    private RerankResponse parse(byte[] result, List<String> passages, long latencyMs, int inputChars) {
        JsonNode root;
        try {
            root = mapper.readTree(result);
        } catch (IOException e) {
            throw new GpuPlaneException("invalid_result", "RERANK result is not JSON", e);
        }
        JsonNode items = root == null ? null : root.get("results");
        if (items == null || !items.isArray()) {
            throw new GpuPlaneException("invalid_result", "RERANK result has no results[]");
        }
        List<RerankResponse.RerankResult> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode item : items) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= passages.size() || !seen.add(index) || !item.has("relevance_score")) {
                throw new GpuPlaneException("invalid_result", "RERANK result has an invalid or duplicate index " + index);
            }
            out.add(new RerankResponse.RerankResult(index, item.get("relevance_score").asDouble(), passages.get(index)));
        }
        if (out.size() != passages.size()) {
            throw new GpuPlaneException("invalid_result",
                    "RERANK scored " + out.size() + " of " + passages.size() + " passages");
        }
        out.sort(Comparator.comparingDouble(RerankResponse.RerankResult::score).reversed());
        int tokens = root.path("usage").path("prompt_tokens").asInt(0);
        return new RerankResponse(out, inputChars, 0, latencyMs, tokens, 0);
    }

    @Override
    public void close() {
        if (ownedChannel != null) {
            ownedChannel.shutdown();
        }
    }
}
