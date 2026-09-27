package org.synanton.bench.convergence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Q3 run-output schema: {@code run_id}, {@code corpus}, {@code queries[]} with
 * per-query {@code query_id, mode, filter, selectivity, top_k[], eligible_set[], timing}.
 *
 * <p>Parsing is strict (schema-diff gotcha): both harnesses must emit exactly
 * this shape. Unknown top-level fields are ignored; missing or mistyped
 * required fields fail loudly with the file path — the comparator never
 * absorbs a schema mismatch silently.
 */
public record RunOutput(String runId, String corpus, List<QueryResult> queries) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static RunOutput parse(InputStream in, String source) throws IOException {
        JsonNode root;
        try {
            root = MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IOException("invalid JSON in " + source + ": " + e.getMessage(), e);
        }
        return parse(root, source);
    }

    static RunOutput parse(JsonNode root, String source) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("run output must be a JSON object: " + source);
        }
        String runId = requiredText(root, "run_id", source);
        String corpus = requiredText(root, "corpus", source);
        JsonNode queries = root.get("queries");
        if (queries == null || !queries.isArray()) {
            throw new IllegalArgumentException("missing required array 'queries': " + source);
        }
        List<QueryResult> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode q : queries) {
            QueryResult r = parseQuery(q, source);
            if (!seen.add(r.queryId() + "|" + r.mode() + "|" + r.filter() + "|" + r.selectivity())) {
                throw new IllegalArgumentException("duplicate query leg in " + source + ": " + r.queryId());
            }
            out.add(r);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("empty 'queries' in " + source + ": nothing to compare");
        }
        return new RunOutput(runId, corpus, List.copyOf(out));
    }

    private static QueryResult parseQuery(JsonNode q, String source) {
        String id = requiredText(q, "query_id", source);
        String mode = requiredText(q, "mode", source);
        String filter = q.has("filter") ? q.get("filter").asText() : "none";
        String selectivity = q.has("selectivity") ? q.get("selectivity").asText() : "-";
        List<TopKEntry> topK = new ArrayList<>();
        JsonNode topKNode = q.get("top_k");
        if (topKNode != null) {
            if (!topKNode.isArray()) {
                throw new IllegalArgumentException(
                        "query '" + id + "': 'top_k' must be an array: " + source);
            }
            for (JsonNode e : topKNode) {
                topK.add(
                        new TopKEntry(
                                requiredText(e, "chunk_id", source + " query '" + id + "'"),
                                e.has("score") ? e.get("score").asDouble() : 0.0,
                                e.has("rank") ? e.get("rank").asInt() : 0));
            }
        }
        List<String> eligible = new ArrayList<>();
        JsonNode eligNode = q.get("eligible_set");
        if (eligNode != null) {
            if (!eligNode.isArray()) {
                throw new IllegalArgumentException(
                        "query '" + id + "': 'eligible_set' must be an array: " + source);
            }
            eligNode.forEach(e -> eligible.add(e.asText()));
        }
        double timing = q.has("timing_ms") ? q.get("timing_ms").asDouble() : -1.0;
        return new QueryResult(id, mode, filter, selectivity, List.copyOf(topK), List.copyOf(eligible), timing);
    }

    private static String requiredText(JsonNode node, String field, String source) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) {
            throw new IllegalArgumentException(
                    "missing required text field '" + field + "': " + source);
        }
        return v.asText();
    }
}
