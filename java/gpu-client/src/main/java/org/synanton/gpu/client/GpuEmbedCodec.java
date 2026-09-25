package org.synanton.gpu.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Codec for the EMBED payload and result. Both are OpenAI-compatible
 * {@code /v1/embeddings} JSON, carried in {@code ExecutionRequest.payload} and
 * {@code ExecutionResponse.result}. {@code model} is always the logical model ID; the
 * Gateway rewrites it (Deployment Plan §4).
 */
public final class GpuEmbedCodec {

    private final ObjectMapper mapper;

    public GpuEmbedCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Result(List<float[]> embeddings, int promptTokens) {}

    public byte[] payload(String model, List<String> inputs) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        inputs.forEach(input::add);
        try {
            return mapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new GpuPlaneException("invalid_request", "cannot serialise EMBED payload", e);
        }
    }

    /**
     * Parses a result into vectors ordered by {@code data[].index}.
     *
     * @param expected the number of inputs sent, or -1 to skip the check. A mismatch means the
     *                 vectors can't be matched back to their inputs, so it throws
     *                 ({@code invalid_result}) instead of returning partial data.
     */
    public Result parse(byte[] result, int expected) {
        JsonNode root;
        try {
            root = mapper.readTree(result);
        } catch (IOException e) {
            throw new GpuPlaneException("invalid_result", "EMBED result is not JSON", e);
        }
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            throw new GpuPlaneException("invalid_result", "EMBED result has no data[]");
        }
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        items.sort(Comparator.comparingInt(n -> n.path("index").asInt(0)));
        List<float[]> vectors = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode emb = item.get("embedding");
            if (emb == null || !emb.isArray() || emb.isEmpty()) {
                throw new GpuPlaneException("invalid_result", "EMBED result item without a vector");
            }
            float[] v = new float[emb.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) emb.get(i).asDouble();
            }
            vectors.add(v);
        }
        if (expected >= 0 && vectors.size() != expected) {
            throw new GpuPlaneException("invalid_result",
                    "EMBED returned " + vectors.size() + " vectors for " + expected + " inputs");
        }
        return new Result(vectors, root.path("usage").path("prompt_tokens").asInt(0));
    }
}
