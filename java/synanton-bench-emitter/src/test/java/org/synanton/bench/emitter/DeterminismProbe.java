package org.synanton.bench.emitter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.bench.emitter.QueryExecutor.QueryOutput;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.inmemory.InMemorySynquestEngine;

/**
 * A.6 determinism probe entry point. Builds a fixed in-memory engine + the
 * full 120-query set (all modes × all filter types, incl. metadata and
 * eligibility legs), runs every query, normalizes timing_ms to 0, prints the
 * sha256 of the normalized Q3. Any subset smaller than the full set would
 * leave paths (e.g. the A.5 metadata columns) unexercised — hence 120.
 */
public final class DeterminismProbe {

    static final List<String> TENANTS = List.of("tenant_07", "tenant_11", "tenant_00");
    static final String[] MODES = {"lexical", "vector", "hybrid"};
    static final String[] FILTERS = {"none", "tenant", "metadata", "eligibility"};
    static final GenerationId GEN = new GenerationId("det-gen-1");
    static final EmbeddingModelRef MODEL = new EmbeddingModelRef("det", "v1", "det");

    private DeterminismProbe() {}

    static String vecB64(float... vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) {
            buf.putFloat(v);
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }

    static InMemorySynquestEngine engine() throws Exception {
        InMemorySynquestEngine engine = new InMemorySynquestEngine();
        List<ChunkProjection> projections = new ArrayList<>();
        String[] texts = {"alpha beta", "beta gamma", "gamma delta", "delta alpha"};
        String[] types = {"memo", "report"};
        long ord = 0;
        for (String tenant : TENANTS) {
            for (int i = 0; i < 4; i++) {
                float[] vec = new float[] {i + 1, TENANTS.indexOf(tenant)};
                projections.add(
                        new ChunkProjection(
                                ChunkId.of("c-" + tenant + "-" + i),
                                DocumentId.of("d-" + tenant),
                                tenant,
                                texts[i % texts.length],
                                Map.of("doc_type", types[i % types.length], "lang", "en"),
                                vec, MODEL, ord++, GEN));
            }
        }
        engine.upsert(projections).toCompletableFuture().join();
        return engine;
    }

    static List<GoldenInput> queries() {
        List<GoldenInput> out = new ArrayList<>(120);
        for (int i = 0; i < 120; i++) {
            String mode = MODES[i % MODES.length];
            String filter = FILTERS[(i / MODES.length) % FILTERS.length];
            List<String> scope =
                    switch (filter) {
                        case "none" -> List.of();
                        case "tenant", "eligibility" -> List.of(TENANTS.get(i % TENANTS.size()));
                        case "metadata" -> List.of(TENANTS.get((i + 1) % TENANTS.size()));
                        default -> throw new IllegalArgumentException(filter);
                    };
            Map<String, String> pred =
                    filter.equals("metadata") ? Map.of("doc_type", "memo") : Map.of();
            String vec = mode.equals("lexical") ? "" : vecB64(1, 0);
            String text = mode.equals("vector") ? "" : "alpha beta";
            out.add(
                    new GoldenInput(
                            String.format("q%03d", i), mode, text, vec, scope, pred,
                            filter.equals("eligibility") ? "1%" : "-",
                            filter, List.of("c-" + TENANTS.get(0) + "-0")));
        }
        return List.copyOf(out);
    }

    /** Normalized Q3 sha (timing_ms zeroed — timing may vary, results must not). */
    static String runSha(InMemorySynquestEngine engine) throws Exception {
        List<QueryOutput> normalized = new ArrayList<>();
        for (GoldenInput in : queries()) {
            QueryOutput o = QueryExecutor.execute(engine, in, TENANTS);
            normalized.add(
                    new QueryOutput(
                            o.queryId(), o.mode(), o.filter(), o.selectivity(), o.topK(),
                            o.eligibleIds(), 0.0, o.timingScope(), o.minScore()));
        }
        String json = Q3Emitter.emit("det-run", "det-corpus", normalized);
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes()));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("det-sha=" + runSha(engine()));
    }
}
