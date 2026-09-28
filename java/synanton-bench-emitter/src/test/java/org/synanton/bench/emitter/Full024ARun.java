package org.synanton.bench.emitter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.bench.emitter.QueryExecutor.QueryOutput;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.cassandra.CassandraSynquestEngine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B.2 full 024A leg (~50 min index load at ~55 rows/s + query run; manual
 * only, never CI): loads the v1 corpus into the 024A adapter, runs all 120
 * golden queries through the shared executor, validates eligible sets, emits
 * {@code runs/024a-v1.json}. Run:
 * {@code CORPUS_DIR=/tmp/corpus-v1 ./gradlew :java:synanton-bench-emitter:test
 * --tests "*Full024ARun" -Dbench.run.024a=true}.
 */
@EnabledIfSystemProperty(named = "bench.run.024a", matches = "true")
class Full024ARun {

    private static final GenerationId GEN = new GenerationId("b2-gen-1");
    private static final EmbeddingModelRef MODEL = new EmbeddingModelRef("b2", "v1", "b2");

    private static float[] decodeVec(String b64) {
        byte[] bytes = Base64.getDecoder().decode(b64);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[bytes.length / 4];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
    }

    @Test
    void full024ALeg() throws Exception {
        // Error-printing discipline: every phase failure prints its phase,
        // message, and stack to stderr BEFORE propagating — a bare Gradle
        // "failing tests" line or silent SKIP must never be the only record.
        Thread.currentThread()
                .setUncaughtExceptionHandler(
                        (t, e) -> {
                            System.err.println("B2-ERROR uncaught thread=" + t.getName() + ": " + e);
                            e.printStackTrace(System.err);
                        });
        try {
            runLeg();
        } catch (Exception e) {
            System.err.println("B2-ERROR phase=" + currentPhase + ": " + e);
            e.printStackTrace(System.err);
            throw e;
        } catch (Throwable t) {
            System.err.println("B2-ERROR phase=" + currentPhase + " (fatal): " + t);
            t.printStackTrace(System.err);
            throw t;
        }
    }

    private static volatile String currentPhase = "init";

    private void runLeg() throws Exception {
        currentPhase = "manifest";
        Path corpusDir = Paths.get(System.getenv().getOrDefault("CORPUS_DIR", "/tmp/corpus-v1"));
        CorpusLoader.Manifest manifest = CorpusLoader.loadManifest(corpusDir);
        assertThat(manifest.corpusVersion()).isEqualTo("ydb-poc-corpus-v1");

        Path root = Files.createTempDirectory("full-024a");
        CassandraSynquestEngine engine = new CassandraSynquestEngine(root);
        currentPhase = "load";

        // Load: stream corpus rows into projections (batched upserts).
        // Streaming discipline (OOM lesson): the batch is cleared after every
        // upsert — never 160k projections in memory at once.
        List<ChunkProjection> batch = new ArrayList<>(2000);
        java.util.concurrent.atomic.AtomicLong loaded = new java.util.concurrent.atomic.AtomicLong();
        CorpusLoader.streamChunks(
                corpusDir,
                row -> {
                    batch.add(
                            new ChunkProjection(
                                    ChunkId.of(row.chunkId()), DocumentId.of(row.docId()),
                                    row.tenantId(), row.text(), Map.of(),
                                    decodeVec(row.embeddingB64()), MODEL, 0L, GEN));
                    if (batch.size() >= 2000) {
                        engine.upsert(List.copyOf(batch)).toCompletableFuture().join();
                        long total = loaded.addAndGet(batch.size());
                        batch.clear();
                        if (total % 20000 == 0) {
                            System.out.println("B2-LOAD loaded=" + total);
                        }
                    }
                });
        if (!batch.isEmpty()) {
            engine.upsert(List.copyOf(batch)).toCompletableFuture().join();
            loaded.addAndGet(batch.size());
            batch.clear();
        }
        // NOTE: orderingKey 0 for all — ordering-guard semantics not under
        // test here; upsert path only.
        System.out.println("B2-LOAD done loaded=" + loaded.get());
        assertThat(loaded.get()).isEqualTo(160_000);
        currentPhase = "query";

        // Tenant universe: tenant_00..49 (spec-fixed ids).
        List<String> universe = new ArrayList<>();
        for (int t = 0; t < 50; t++) {
            universe.add(String.format("tenant_%02d", t));
        }

        // Queries: stream golden-queries.jsonl (Jackson), execute, validate, emit.
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<QueryOutput> outputs = new ArrayList<>();
        int qcount = 0;
        try (var reader =
                Files.newBufferedReader(corpusDir.resolve("golden-queries.jsonl"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                var q = mapper.readTree(line);
                List<String> scope = new ArrayList<>();
                q.get("tenant_scope").forEach(s -> scope.add(s.asText()));
                Map<String, String> pred = new java.util.LinkedHashMap<>();
                q.get("metadata_predicate").fields().forEachRemaining(e -> pred.put(e.getKey(), e.getValue().asText()));
                List<String> eligible = new ArrayList<>();
                q.get("eligible_chunk_ids").forEach(e -> eligible.add(e.asText()));
                QueryOutput out =
                        QueryExecutor.execute(
                                engine,
                                new GoldenInput(
                                        q.get("query_id").asText(), q.get("mode").asText(),
                                        q.get("text").asText(),
                                        q.has("query_vector_b64") ? q.get("query_vector_b64").asText() : "",
                                        scope, pred,
                                        q.has("selectivity") ? q.get("selectivity").asText() : "-",
                                        q.get("filter").asText(), eligible),
                                universe);
                // Ground-truth validation inline (A.5 rule): eligible_set must
                // match re-derivation from the loaded corpus state.
                outputs.add(out);
                if (++qcount % 20 == 0) {
                    System.out.println("B2-QUERY done=" + qcount);
                }
            }
        }
        assertThat(qcount).isEqualTo(120);

        // Eligible-set validation (A.5 rule, single in-memory pass):
        // re-derive from corpus rows per spec §5, set-compare vs fixture.
        currentPhase = "validation";
        record RowMeta(String tenant, Map<String, String> meta) {}
        var meta = new java.util.HashMap<String, RowMeta>();
        CorpusLoader.streamChunks(
                corpusDir,
                row ->
                        meta.put(
                                row.chunkId(),
                                new RowMeta(row.tenantId(), Map.copyOf(row.metadata()))));
        try (var reader = Files.newBufferedReader(corpusDir.resolve("golden-queries.jsonl"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                var q = mapper.readTree(line);
                List<String> scope = new ArrayList<>();
                q.get("tenant_scope").forEach(s -> scope.add(s.asText()));
                Map<String, String> pred = new java.util.LinkedHashMap<>();
                q.get("metadata_predicate")
                        .fields()
                        .forEachRemaining(e -> pred.put(e.getKey(), e.getValue().asText()));
                var expected = new java.util.HashSet<String>();
                meta.forEach(
                        (id, rm) -> {
                            if (!scope.isEmpty() && !scope.contains(rm.tenant())) {
                                return;
                            }
                            if (pred.entrySet().stream()
                                    .allMatch(
                                            e ->
                                                    e.getValue()
                                                            .equals(rm.meta().getOrDefault(e.getKey(), "")))) {
                                expected.add(id);
                            }
                        });
                var actual = new java.util.HashSet<String>();
                q.get("eligible_chunk_ids").forEach(e -> actual.add(e.asText()));
                assertThat(actual)
                        .as("eligible_set matches re-derivation for " + q.get("query_id").asText())
                        .isEqualTo(expected);
            }
        }
        System.out.println("B2-ELIGIBILITY all 120 queries match re-derivation");
        currentPhase = "emission";

        String json = Q3Emitter.emit("024a-v1", manifest.corpusVersion(), outputs);
        Path runs = Paths.get("runs");
        Path written = Q3Emitter.write(runs, "024a", json);
        System.out.println("B2-DONE wrote=" + written.toAbsolutePath() + " queries=" + outputs.size());
    }
}
