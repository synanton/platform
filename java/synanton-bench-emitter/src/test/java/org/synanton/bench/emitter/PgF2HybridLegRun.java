package org.synanton.bench.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.bench.emitter.QueryExecutor.QueryOutput;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.HttpLlmClient;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.postgres.PostgresSynquestEngine;
import org.synanton.synvault.postgres.PostgresSchema;

/**
 * F-2 PG hybrid leg with REAL vectors: 81 demo-corpus chunks at 384d
 * (synanton-bge-small-embedding, native to the {@code vector(384)} column)
 * backfilled from the ingestion cache; F-3 queries embedded live via TEI-small.
 * Model confound vs the 768d legs is stated, not hidden (framework F-9).
 * Requires bench-postgres (:5433), bench Cassandra (:9042), TEI-small (:30801).
 */
class PgF2HybridLegRun {

    private static final String URL = System.getenv()
            .getOrDefault("BENCH_PG_URL", "jdbc:postgresql://localhost:5433/bench");
    private static final String ADMIN = System.getenv().getOrDefault("BENCH_PG_USER", "bench");
    private static final String ADMIN_PASSWORD =
            System.getenv().getOrDefault("BENCH_PG_PASSWORD", "bench");
    private static final EmbeddingModelRef MODEL = EmbeddingModelRef.of(
            "synanton-bge-small-embedding", "bge-small-en-v1.5", "tei-1.9");
    private static final GenerationId GEN = new GenerationId("f2-gen-1");
    private static final String TENANT = "demo";
    /**
     * PG vector score space is {@code -distance} (negative). pgvector's
     * {@code <=>} is cosine distance in [0, 2], so -2.0 keeps every hit —
     * the backend-local neutral floor (QueryExecutor vectorFloor contract).
     */
    private static final double PG_VECTOR_FLOOR = -2.0;

    private record TextQuery(String id, String text) {}

    private static final List<TextQuery> QUERIES = loadQueries();

    private static List<TextQuery> loadQueries() {
        String file = System.getenv("BENCH_QUERIES_FILE");
        if (file != null && !file.isBlank()) {
            // Single query source (F-3): [{query_id, text, ...}, ...]; gold lives alongside.
            try {
                String json = java.nio.file.Files.readString(java.nio.file.Path.of(file));
                List<TextQuery> out = new ArrayList<>();
                for (String part : json.split("\"query_id\"")) {
                    String[] q = part.split("\"", -1);
                    if (q.length < 2 || q[1].isBlank()) {
                        continue;
                    }
                    String[] t = part.split("\"text\"", -1);
                    if (t.length < 2) {
                        continue;
                    }
                    String[] tv = t[1].split("\"", -1);
                    if (tv.length < 2 || tv[1].isBlank()) {
                        continue;
                    }
                    out.add(new TextQuery(q[1], tv[1]));
                }
                if (!out.isEmpty()) {
                    return List.copyOf(out);
                }
            } catch (Exception e) {
                throw new IllegalStateException("cannot load BENCH_QUERIES_FILE=" + file, e);
            }
        }
        return QUERIES_DEFAULT;
    }

    private static final List<TextQuery> QUERIES_DEFAULT = List.of(
            new TextQuery("f1-q1", "company profile industrial manufacturing"),
            new TextQuery("f1-q2", "supplier precision metal components automotive"),
            new TextQuery("f1-q3", "compliance report quarterly logistics network"));

    private static String b64(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.asFloatBuffer().put(vec);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    @Test
    void shouldProducePgF2Artifact() throws Exception {
        String cassandraHost = System.getenv().getOrDefault("BENCH_CASSANDRA_HOST", "localhost");
        int cassandraPort = Integer.parseInt(System.getenv().getOrDefault("BENCH_CASSANDRA_PORT", "9042"));
        String teiBase = System.getenv().getOrDefault("BENCH_TEI_SMALL_BASE_URL", "http://localhost:30801/v1");

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

        List<ChunkProjection> rows = new ArrayList<>();
        long ordering = 1;
        try (CqlSession session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(cassandraHost, cassandraPort))
                .withLocalDatacenter("datacenter1")
                .withKeyspace("ingestion_cache")
                .build()) {
            IngestionCacheClient cache = new IngestionCacheClient(session);
            List<ManifestRow> manifests = cache.listManifest(TENANT, 200_000);
            assertThat(manifests).isNotEmpty();
            for (ManifestRow manifest : manifests) {
                Map<String, EmbeddingRow> embeddings = cache
                        .listEmbeddings(TENANT, manifest.contentRefId()).stream()
                        .filter(e -> MODEL.modelId().equals(e.modelId()))
                        .collect(Collectors.toMap(
                                e -> e.contentRefId() + "#" + e.chunkOrdinal(), e -> e, (a, b) -> a));
                for (ChunkRow chunk : cache.readChunks(TENANT, manifest.contentRefId())) {
                    EmbeddingRow e = embeddings.get(
                            chunk.contentRefId() + "#" + chunk.chunkOrdinal());
                    if (e == null || chunk.chunkText() == null || chunk.chunkText().isBlank()) {
                        continue;
                    }
                    assertThat(e.embeddingDim()).isEqualTo(384);
                    rows.add(new ChunkProjection(
                            ChunkId.of(chunk.contentRefId().toString()),
                            DocumentId.of("d-" + chunk.contentRefId()),
                            TENANT,
                            chunk.chunkText(),
                            Map.of(),
                            e.embedding(),
                            MODEL,
                            ordering++,
                            GEN));
                }
            }
        }
        assertThat(rows).hasSizeGreaterThanOrEqualTo(81);
        engine.upsert(rows).toCompletableFuture().join();

        HttpLlmClient tei = new HttpLlmClient(teiBase, 3);
        List<QueryOutput> outputs = new ArrayList<>();
        for (TextQuery query : QUERIES) {
            float[] vector = tei.embed(new EmbedRequest(MODEL.modelId(), List.of(query.text())))
                    .embeddings().get(0);
            assertThat(vector).hasSize(384);
            GoldenInput input = new GoldenInput(
                    query.id(), "vector", query.text(), b64(vector),
                    List.of(TENANT), Map.of(), "-", "none", List.of());
            outputs.add(QueryExecutor.execute(engine, input, List.of(TENANT), PG_VECTOR_FLOOR, MODEL));
        }

        String runId = System.getenv().getOrDefault("BENCH_RUN_ID", "pg-f2-r1");
        String json = Q3Emitter.emit(runId, "demo-frozen-v1", outputs);
        Path out = Q3Emitter.write(Paths.get("runs"), runId, json);

        assertThat(out.toFile().exists()).isTrue();
        assertThat(outputs).hasSize(QUERIES.size());
        assertThat(outputs.stream().mapToInt(o -> o.topK().size()).sum()).isGreaterThan(0);
    }
}
