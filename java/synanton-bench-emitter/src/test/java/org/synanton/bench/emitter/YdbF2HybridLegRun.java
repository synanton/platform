package org.synanton.bench.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
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
import org.synanton.synquest.ydb.YdbSearchSchema;
import org.synanton.synquest.ydb.YdbSynquestEngine;
import tech.ydb.table.TableClient;
import tech.ydb.core.grpc.GrpcTransport;

/**
 * F-2 YDB hybrid leg with REAL vectors: 81 demo-corpus chunks at 384d
 * (synanton-bge-small-embedding) backfilled from the ingestion cache;
 * F-3 queries embedded live via TEI-small; HYBRID mode (native HybridRank
 * fulltext+KNN fusion). Model confound vs the 768d legs stated (F-9).
 * Requires ydb-poc (:2135 + CA), bench Cassandra (:9042), TEI-small (:30801).
 */
class YdbF2HybridLegRun {

    private static final EmbeddingModelRef MODEL = EmbeddingModelRef.of(
            "synanton-bge-small-embedding", "bge-small-en-v1.5", "tei-1.9");
    private static final GenerationId GEN = new GenerationId("f2-gen-1");
    private static final String TENANT =
            System.getenv().getOrDefault("BENCH_TENANT", "demo");

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

    private static TableClient tableClient;
    private static GrpcTransport transport;
    private static String prefix;

    private static String b64(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.asFloatBuffer().put(vec);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static TableClient client() throws Exception {
        if (tableClient != null) {
            return tableClient;
        }
        String caPath = firstExisting(
                System.getenv().getOrDefault("YDB_CA_PATH", ""),
                System.getProperty("ydb.ca.path", ""),
                "/tmp/ydb-ca.pem",
                System.getProperty("user.home") + "/.config/ydb-ca.pem");
        byte[] ca = Files.readAllBytes(Paths.get(caPath));
        transport = GrpcTransport.forConnectionString(
                        System.getenv().getOrDefault("YDB_ENDPOINT", "grpcs://localhost:2135/local"))
                .withSecureConnection(ca)
                .build();
        tableClient = TableClient.newClient(transport).build();
        return tableClient;
    }

    private static String firstExisting(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()
                    && Files.isReadable(Paths.get(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "YDB CA not found; copy it out with: docker cp ydb-poc:/ydb_certs/ca.pem /tmp/ydb-ca.pem");
    }

    @AfterAll
    static void dropSchema() {
        if (prefix != null) {
            YdbSearchSchema.dropSchema(tableClient, prefix);
            tableClient.close();
            transport.close();
        }
    }

    @Test
    void shouldProduceYdbF2Artifact() throws Exception {
        String cassandraHost = System.getenv().getOrDefault("BENCH_CASSANDRA_HOST", "localhost");
        int cassandraPort = Integer.parseInt(System.getenv().getOrDefault("BENCH_CASSANDRA_PORT", "9042"));
        String teiBase = System.getenv().getOrDefault("BENCH_TEI_SMALL_BASE_URL", "http://localhost:30801/v1");

        // Bench-stack legs: skip cleanly where Cassandra is absent (CI)
        // instead of failing the build; runs wherever the stacks are up.
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new InetSocketAddress(cassandraHost, cassandraPort), 3000);
        } catch (java.io.IOException expectedWithoutBenchStack) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "bench Cassandra unreachable — skipping leg (CI-safe)");
        }
        // Same for YDB itself (default grpcs://localhost:2135/local).
        String ydbEndpoint = System.getenv().getOrDefault("YDB_ENDPOINT", "grpcs://localhost:2135/local");
        java.util.regex.Matcher ydbUrl = java.util.regex.Pattern.compile("://([^:/]+)(:(\\d+))?/")
                .matcher(ydbEndpoint);
        boolean ydbParsed = ydbUrl.find();
        String ydbHost = ydbParsed ? ydbUrl.group(1) : "invalid";
        int ydbPort = (ydbParsed && ydbUrl.group(3) != null) ? Integer.parseInt(ydbUrl.group(3)) : 2135;
        try (java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new InetSocketAddress(ydbHost, ydbPort), 3000);
        } catch (java.io.IOException expectedWithoutBenchStack) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "bench YDB unreachable — skipping leg (CI-safe)");
        }

        TableClient client = client();
        prefix = "t_f2_ydb_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        YdbSearchSchema.ensureSchema(client, prefix, 384);
        YdbSynquestEngine engine = new YdbSynquestEngine(client, prefix);

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
                    query.id(), "hybrid", query.text(), b64(vector),
                    List.of(TENANT), Map.of(), "-", "none", List.of());
            outputs.add(QueryExecutor.execute(engine, input, List.of(TENANT), 0.0, MODEL));
        }

        String runId = System.getenv().getOrDefault("BENCH_RUN_ID", "ydb-f2-r1");
        String json = Q3Emitter.emit(runId, "demo-frozen-v1", outputs);
        Path out = Q3Emitter.write(Paths.get("runs"), runId, json);

        assertThat(out.toFile().exists()).isTrue();
        assertThat(outputs).hasSize(QUERIES.size());
        assertThat(outputs.stream().mapToInt(o -> o.topK().size()).sum()).isGreaterThan(0);
    }
}
