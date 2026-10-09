package org.synanton.bench.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.bench.emitter.QueryExecutor.QueryOutput;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.postgres.PostgresSynquestEngine;
import org.synanton.synvault.postgres.PostgresSchema;

/**
 * VEC-B5.2 compositions 4/5 (PG+Milvus, PG+Qdrant), lexical legs against live
 * bench PG: fresh schema, mini-corpus ingest, gold queries through
 * {@link QueryExecutor}, Q3 artifact to runs/. The metadata leg is identical
 * for both compositions (vector legs need embeddings + vector stacks —
 * recorded, not fudged), so one PG run serves comps 4 and 5.
 * Requires bench PG on localhost:5433 (docker-compose.bench.yml postgres).
 */
class B5PgLegRun {

    private static final String URL = System.getenv()
            .getOrDefault("BENCH_PG_URL", "jdbc:postgresql://localhost:5433/bench");
    private static final String ADMIN = System.getenv().getOrDefault("BENCH_PG_USER", "bench");
    private static final String ADMIN_PASSWORD =
            System.getenv().getOrDefault("BENCH_PG_PASSWORD", "bench");
    private static final EmbeddingModelRef MODEL = EmbeddingModelRef.of("bench", "v1", "bench");
    private static final GenerationId GEN = new GenerationId("b5-gen-1");

    private record GoldQuery(String id, String tenant, String text, List<String> gold, List<String> eligible) {}

    private static final List<GoldQuery> QUERIES = List.of(
            new GoldQuery("b5q1", "tenant_07", "alpha", List.of("c1"), List.of("c1", "c2")),
            new GoldQuery("b5q2", "tenant_07", "gamma", List.of("c2"), List.of("c1", "c2")),
            new GoldQuery("b5q3", "tenant_11", "delta", List.of("c3", "c4"), List.of("c3", "c4")),
            new GoldQuery("b5q4", "tenant_11", "alpha", List.of("c4"), List.of("c3", "c4")));

    @Test
    void shouldProducePgB5Artifact() throws Exception {
        try (var admin = java.sql.DriverManager.getConnection(URL, ADMIN, ADMIN_PASSWORD);
                var drop = admin.createStatement()) {
            drop.execute("DROP TABLE IF EXISTS documents, chunks, provenance,"
                    + " publication_log, quest_generations CASCADE");
            PostgresSchema.ensureSchema(admin);
            drop.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app')"
                    + " THEN CREATE ROLE app NOSUPERUSER LOGIN PASSWORD 'app'; END IF; END $$");
            drop.execute("GRANT USAGE ON SCHEMA public TO app");
            drop.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO app");
        }
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(URL);
        ds.setUser("app");
        ds.setPassword("app");
        PostgresSynquestEngine engine = new PostgresSynquestEngine(ds);

        Map<String, String> texts =
                Map.of("c1", "alpha beta", "c2", "beta gamma", "c3", "gamma delta", "c4", "delta alpha");
        Map<String, String> tenants =
                Map.of("c1", "tenant_07", "c2", "tenant_07", "c3", "tenant_11", "c4", "tenant_11");
        List<ChunkProjection> rows = new ArrayList<>();
        long ordering = 1;
        for (String id : List.of("c1", "c2", "c3", "c4")) {
            rows.add(
                    new ChunkProjection(
                            ChunkId.of(id),
                            DocumentId.of("d-" + id),
                            tenants.get(id),
                            texts.get(id),
                            Map.of(),
                            null,
                            MODEL,
                            ordering++,
                            GEN));
        }
        engine.upsert(rows).toCompletableFuture().join();

        List<QueryOutput> outputs = new ArrayList<>();
        for (GoldQuery query : QUERIES) {
            GoldenInput input = new GoldenInput(
                    query.id(), "lexical", query.text(), "",
                    List.of(query.tenant()), Map.of(), "-", "none", query.eligible());
            outputs.add(QueryExecutor.execute(engine, input, List.of(query.tenant())));
        }

        String json = Q3Emitter.emit("pg-b5-r1", "pg-mini-b5", outputs);
        Path out = Q3Emitter.write(Paths.get("runs"), "pg-b5-r1", json);

        assertThat(out.toFile().exists()).isTrue();
        assertThat(outputs).hasSize(4);
        assertThat(outputs.stream().mapToInt(o -> o.topK().size()).sum()).isGreaterThan(0);
    }
}
