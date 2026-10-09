package org.synanton.bench.emitter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.synanton.bench.emitter.QueryExecutor.GoldenInput;
import org.synanton.bench.emitter.QueryExecutor.QueryOutput;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.ydb.YdbSearchSchema;
import org.synanton.synquest.ydb.YdbSynquestEngine;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.TableClient;

/**
 * VEC-B5.2 composition 6 (YDB+YDB), lexical legs: ingests a mini corpus into a
 * fresh YDB prefix and runs gold queries through {@link QueryExecutor},
 * emitting a Q3 artifact to runs/. Dense legs need real embeddings (only mock
 * constant vectors are reachable) — recorded, not fudged. Requires ydb-poc
 * on localhost:2135 (dev-guide-tests.md setup).
 */
class B5YdbLegRun {

    private static final EmbeddingModelRef MODEL = EmbeddingModelRef.of("bench", "v1", "bench");
    private static final GenerationId GEN = new GenerationId("b5-gen-1");

    private record GoldQuery(String id, String tenant, String text, List<String> gold, List<String> eligible) {}

    private static final List<GoldQuery> QUERIES = List.of(
            new GoldQuery("b5q1", "tenant_07", "alpha", List.of("c1"), List.of("c1", "c2")),
            new GoldQuery("b5q2", "tenant_07", "gamma", List.of("c2"), List.of("c1", "c2")),
            new GoldQuery("b5q3", "tenant_11", "delta", List.of("c3", "c4"), List.of("c3", "c4")),
            new GoldQuery("b5q4", "tenant_11", "alpha", List.of("c4"), List.of("c3", "c4")));

    private static GrpcTransport transport;
    private static TableClient tableClient;
    private static String prefix;

    private static synchronized TableClient client() throws Exception {
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
    void shouldProduceYdbB5Artifact() throws Exception {
        TableClient client = client();
        prefix = "t_b5_ydb_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        YdbSearchSchema.ensureSchema(client, prefix, 2);
        YdbSynquestEngine engine = new YdbSynquestEngine(client, prefix);

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

        String json = Q3Emitter.emit("ydb-b5-r1", "ydb-mini-b5", outputs);
        java.nio.file.Path out = Q3Emitter.write(Paths.get("runs"), "ydb-b5-r1", json);

        assertThat(out.toFile().exists()).isTrue();
        assertThat(outputs).hasSize(4);
        assertThat(outputs.stream().mapToInt(o -> o.topK().size()).sum()).isGreaterThan(0);
    }
}
