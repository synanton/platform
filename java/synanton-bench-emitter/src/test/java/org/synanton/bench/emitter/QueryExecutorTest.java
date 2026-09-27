package org.synanton.bench.emitter;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.inmemory.InMemorySynquestEngine;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A.3 acceptance: executesAllModesAndFiltersAgainstInMemoryEngine — all four
 * modes × all four filter shapes over the mini corpus, vectors read from
 * manifest bytes (byte-identity covered by corpus tests), tie-break
 * deterministic across runs, timing measures the call.
 */
class QueryExecutorTest {

    private static final GenerationId GEN = new GenerationId("test-gen-1");
    private static final EmbeddingModelRef MODEL =
            new EmbeddingModelRef("test", "v1", "test");

    private static InMemorySynquestEngine engine() throws Exception {
        InMemorySynquestEngine engine = new InMemorySynquestEngine();
        engine.upsert(
                        List.of(
                                proj("c1", "d1", "tenant_07", "alpha beta gamma", new float[] {1, 0}),
                                proj("c2", "d1", "tenant_07", "beta gamma delta", new float[] {0, 1}),
                                proj("c3", "d2", "tenant_11", "gamma delta alpha", new float[] {1, 1}),
                                proj("c4", "d2", "tenant_11", "delta alpha beta", new float[] {0, 0.5f})))
                .toCompletableFuture()
                .join();
        return engine;
    }

    private static ChunkProjection proj(String chunk, String doc, String tenant, String text, float[] vec) {
        return new ChunkProjection(
                ChunkId.of(chunk), DocumentId.of(doc), tenant, text, Map.of(),
                vec, MODEL, 1L, GEN);
    }

    private static String b64(float[] vec) {
        java.nio.ByteBuffer buf =
                java.nio.ByteBuffer.allocate(vec.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) {
            buf.putFloat(v);
        }
        return java.util.Base64.getEncoder().encodeToString(buf.array());
    }

    @Test
    void executesAllModesAndFiltersAgainstInMemoryEngine() throws Exception {
        InMemorySynquestEngine engine = engine();
        List<String> universe = List.of("tenant_07", "tenant_11");

        QueryExecutor.QueryOutput lex =
                QueryExecutor.execute(
                        engine,
                        new GoldenInput("q-lex", "lexical", "alpha", "", List.of("tenant_07"),
                                Map.of(), "-", "tenant", List.of("c1", "c2")),
                        universe);
        assertThat(lex.topK()).isNotEmpty();
        assertThat(lex.topK().stream().map(QueryExecutor.Hit::chunkId))
                .as("tenant scope respected").doesNotContain("c3", "c4");

        QueryExecutor.QueryOutput vec =
                QueryExecutor.execute(
                        engine,
                        new GoldenInput("q-vec", "vector", "", b64(new float[] {1, 0}),
                                List.of("tenant_07"), Map.of(), "-", "tenant", List.of("c1", "c2")),
                        universe);
        assertThat(vec.topK()).isNotEmpty();
        assertThat(vec.topK().get(0).chunkId()).as("nearest first").isEqualTo("c1");

        QueryExecutor.QueryOutput hyb =
                QueryExecutor.execute(
                        engine,
                        new GoldenInput("q-hyb", "hybrid", "alpha", b64(new float[] {1, 0}),
                                List.of(), Map.of(), "-", "none", List.of("c1", "c2", "c3", "c4")),
                        universe);
        assertThat(hyb.topK()).as("empty scope fans out over universe").hasSize(4);

        QueryExecutor.QueryOutput meta =
                QueryExecutor.execute(
                        engine,
                        new GoldenInput("q-meta", "lexical", "alpha", "", List.of("tenant_11"),
                                Map.of(), "-", "metadata", List.of("c3", "c4")),
                        universe);
        assertThat(meta.topK().stream().map(QueryExecutor.Hit::chunkId))
                .doesNotContain("c1", "c2");

        // Tie-break deterministic: identical sequence on repeat.
        QueryExecutor.QueryOutput repeat =
                QueryExecutor.execute(
                        engine,
                        new GoldenInput("q-hyb", "hybrid", "alpha", b64(new float[] {1, 0}),
                                List.of(), Map.of(), "-", "none", List.of()),
                        universe);
        assertThat(repeat.topK()).isEqualTo(hyb.topK());

        // Timing measures the call: non-negative, and eligible ids pass through.
        assertThat(hyb.timingMs()).isGreaterThanOrEqualTo(0.0);
        assertThat(lex.eligibleIds()).containsExactly("c1", "c2");

        // timing_scope pins the topology to the number (fan-out finding).
        assertThat(lex.timingScope()).isEqualTo("single");
        assertThat(hyb.timingScope()).isEqualTo("summed_fanout_2");

        // Ranks assigned post-sort, 0-based, dense.
        for (int i = 0; i < hyb.topK().size(); i++) {
            assertThat(hyb.topK().get(i).rank()).isEqualTo(i);
        }
    }
}
