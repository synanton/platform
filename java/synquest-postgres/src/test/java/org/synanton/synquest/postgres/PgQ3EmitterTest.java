package org.synanton.synquest.postgres;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.bench.convergence.RunOutput;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.SearchMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG-POC-007-8 emitter wiring proof: PG Q3 output satisfies the shared
 * emitter contract (strict {@code RunOutput} parse — the same gate every
 * emitter's output passes before reaching the comparator). Six legs
 * (3 modes × none/metadata) over a deterministic fixture corpus.
 */
class PgQ3EmitterTest extends QuestPostgresFixture {

    private static final String TENANT = "emit";

    private static final List<PgQ3Emitter.Leg> LEGS =
            List.of(
                    new PgQ3Emitter.Leg("q-lex-none", SearchMode.LEXICAL, "none", Map.of(),
                            "emitter wheat field", null, 0.0),
                    new PgQ3Emitter.Leg("q-lex-meta", SearchMode.LEXICAL, "metadata",
                            Map.of("type", "runbook"), "emitter wheat field", null, 0.0),
                    new PgQ3Emitter.Leg("q-vec-none", SearchMode.VECTOR, "none", Map.of(),
                            "ignored", new float[] {1.0f, 0.0f}, -10.0),
                    new PgQ3Emitter.Leg("q-vec-meta", SearchMode.VECTOR, "metadata",
                            Map.of("type", "runbook"), "ignored", new float[] {1.0f, 0.0f}, -10.0),
                    new PgQ3Emitter.Leg("q-hyb-none", SearchMode.HYBRID, "none", Map.of(),
                            "emitter wheat field", new float[] {1.0f, 0.0f}, 0.0),
                    new PgQ3Emitter.Leg("q-hyb-meta", SearchMode.HYBRID, "metadata",
                            Map.of("type", "runbook"), "emitter wheat field",
                            new float[] {1.0f, 0.0f}, 0.0));

    @BeforeAll
    static void seed() throws Exception {
        ensureStarted();
        resetTenants(TENANT);
        var engine = QuestPostgresFixture.newEngine();
        List<ChunkProjection> batch = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            boolean runbook = i % 3 == 0;
            batch.add(
                    new ChunkProjection(
                            ChunkId.of(String.format("emit-%02d", i)),
                            DocumentId.of("doc-emit-" + i),
                            TENANT,
                            "emitter wheat field harvest " + i,
                            Map.of("type", runbook ? "runbook" : "note"),
                            runbook ? new float[] {1.0f, 0.0f} : new float[] {0.0f, 1.0f},
                            EmbeddingModelRef.of("m", "v1", "d"),
                            i,
                            GenerationId.of("gen-emit")));
        }
        engine.upsert(batch).toCompletableFuture().join();
    }

    private static Path manifest() {
        // Test-classes dir sibling: src/test/resources lands on the test classpath.
        return Paths.get("src/test/resources/emit-manifest.json").toAbsolutePath();
    }

    private static String emit(String runId) throws Exception {
        var emitter = new PgQ3Emitter(QuestPostgresFixture.newEngine(), manifest());
        return emitter.emit(runId, TENANT, LEGS);
    }

    private static RunOutput parse(String json, String source) throws Exception {
        return RunOutput.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), source);
    }

    @Test
    void pgOutputSatisfiesEmitterContract() throws Exception {
        RunOutput run = parse(emit("pg-v1"), "pg-v1.json");
        assertThat(run.runId()).isEqualTo("pg-v1");
        assertThat(run.corpus()).isEqualTo("pg-q3-fixture-v1");
        assertThat(run.queries()).hasSize(6);
        assertThat(run.queries())
                .filteredOn(q -> q.filter().equals("metadata"))
                .as("every filtered leg carries eligible_set")
                .allSatisfy(q -> assertThat(q.eligibleSet()).isNotEmpty());
    }

    @Test
    void resumeVsFreshHoldsModuloTiming() throws Exception {
        // run_id deterministic, corpus pinned, rankings stable: two emits
        // differ only in timing_ms (wall clock, not a verdict input).
        RunOutput first = parse(emit("pg-v1"), "pg-v1-a.json");
        RunOutput second = parse(emit("pg-v1"), "pg-v1-b.json");
        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.corpus()).isEqualTo(first.corpus());
        assertThat(second.queries()).hasSize(first.queries().size());
        for (int i = 0; i < first.queries().size(); i++) {
            var a = first.queries().get(i);
            var b = second.queries().get(i);
            assertThat(b.queryId()).isEqualTo(a.queryId());
            assertThat(b.topK()).as("stable ranking " + a.queryId()).isEqualTo(a.topK());
            assertThat(b.eligibleSet()).isEqualTo(a.eligibleSet());
        }
    }

    @Test
    void missingManifestFailsLoudly() {
        assertThatThrownBy(
                        () -> new PgQ3Emitter(
                                QuestPostgresFixture.newEngine(),
                                Paths.get("src/test/resources/no-such-manifest.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never guessed");
    }
}
