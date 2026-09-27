package org.synanton.bench.emitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.synanton.bench.emitter.QueryExecutor.Hit;
import org.synanton.bench.emitter.QueryExecutor.QueryOutput;

/**
 * A.4 pure Q3 serializer. Input is {@link QueryOutput} (already normalized:
 * tie-broken, minScore-neutral, eligible ids from fixture ground truth).
 * Ground-truth reads come from golden-queries.jsonl; this class only
 * serializes what it is given — validation is A.5's job.
 */
public final class Q3Emitter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Q3Emitter() {}

    public static String emit(String runId, String corpus, List<QueryOutput> outputs) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("run_id", runId);
        root.put("corpus", corpus);
        ArrayNode queries = root.putArray("queries");
        for (QueryOutput o : outputs) {
            ObjectNode q = MAPPER.createObjectNode();
            q.put("query_id", o.queryId());
            q.put("mode", o.mode());
            q.put("filter", o.filter());
            q.put("selectivity", o.selectivity());
            q.put("timing_ms", o.timingMs());
            q.put("timing_scope", o.timingScope());
            ArrayNode topK = q.putArray("top_k");
            for (Hit h : o.topK()) {
                ObjectNode e = MAPPER.createObjectNode();
                e.put("chunk_id", h.chunkId());
                e.put("score", h.score());
                e.put("rank", h.rank());
                topK.add(e);
            }
            ArrayNode eligible = q.putArray("eligible_set");
            o.eligibleIds().forEach(eligible::add);
            queries.add(q);
        }
        return root.toString();
    }

    public static Path write(Path dir, String leg, String json) throws Exception {
        Files.createDirectories(dir);
        Path out = dir.resolve(leg + "-v1.json");
        Files.writeString(out, json);
        return out;
    }
}
