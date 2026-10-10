package org.synanton.synquest.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.VectorProjection;
import org.synanton.synquest.api.VectorSearchRequest;
import org.synanton.synquest.api.VectorSearchResult;

/**
 * VEC-B5.2 compositions 3/5 (Cassandra+Qdrant, PG+Qdrant), vector legs against
 * live Qdrant: distinct seed vectors per chunk, self-match queries, Q3 artifact
 * to runs/. Requires qdrant-bench on localhost:6334 (scripts/bench-vector-stacks.sh).
 * Recall here measures index correctness (exact self-match), not embedding
 * quality — stated, not hidden.
 */
class QdrantB5LegRun {

    private static final EmbeddingModelRef MODEL = EmbeddingModelRef.of("bench", "v1", "bench");
    private static final GenerationId GEN = new GenerationId("b5-gen-1");
    private static final ObjectMapper JSON = new ObjectMapper();

    private record GoldQuery(String id, String tenant, float[] vector, List<String> eligible) {}

    private static final List<GoldQuery> QUERIES = List.of(
            new GoldQuery("b5q1", "tenant_07", new float[] {1.0f, 0.0f}, List.of("c1", "c2")),
            new GoldQuery("b5q2", "tenant_07", new float[] {0.0f, 1.0f}, List.of("c1", "c2")),
            new GoldQuery("b5q3", "tenant_11", new float[] {0.7f, 0.7f}, List.of("c3", "c4")),
            new GoldQuery("b5q4", "tenant_11", new float[] {-1.0f, 0.0f}, List.of("c3", "c4")));

    private static QdrantClient client;

    private static synchronized QdrantClient client() {
        if (client == null) {
            String host = System.getenv().getOrDefault("BENCH_QDRANT_HOST", "localhost");
            int port = Integer.parseInt(System.getenv().getOrDefault("BENCH_QDRANT_PORT", "6334"));
            QdrantGrpcClient grpc = QdrantGrpcClient.newBuilder(host, port, false).build();
            client = new QdrantClient(grpc);
        }
        return client;
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                throw new IllegalStateException("qdrant client teardown failed", e);
            }
        }
    }

    @Test
    void shouldProduceQdrantB5Artifact() throws Exception {
        String collection =
                "t_b5_qdrant_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        QdrantVectorRetriever adapter = new QdrantVectorRetriever(client(), collection, 2);

        Map<String, float[]> vectors = Map.of(
                "c1", new float[] {1.0f, 0.0f},
                "c2", new float[] {0.0f, 1.0f},
                "c3", new float[] {0.7f, 0.7f},
                "c4", new float[] {-1.0f, 0.0f});
        Map<String, String> tenants =
                Map.of("c1", "tenant_07", "c2", "tenant_07", "c3", "tenant_11", "c4", "tenant_11");
        List<VectorProjection> rows = new ArrayList<>();
        long ordering = 1;
        for (String id : List.of("c1", "c2", "c3", "c4")) {
            rows.add(
                    new VectorProjection(
                            ChunkId.of(id),
                            DocumentId.of("d-" + id),
                            tenants.get(id),
                            vectors.get(id),
                            MODEL,
                            ordering++,
                            GEN));
        }
        adapter.upsert(rows).toCompletableFuture().join();

        List<Map<String, Object>> queries = new ArrayList<>();
        for (GoldQuery query : QUERIES) {
            SecurityContext context = SecurityContext.user(
                    new TenantScope(query.tenant()),
                    new PrincipalRef("user", "u1"),
                    new PolicyContext("p", "1"));
            EligibilityConstraints eligibility = EligibilityConstraints.from(
                    new TenantScope(query.tenant()),
                    List.of(new PrincipalRef("user", "u1")),
                    new PolicyContext("p", "1"));
            long start = System.nanoTime();
            VectorSearchResult result = adapter
                    .search(
                            context,
                            new VectorSearchRequest(query.vector(), Optional.of(MODEL), eligibility, 10))
                    .toCompletableFuture()
                    .join();
            double timingMs = (System.nanoTime() - start) / 1_000_000.0;
            List<Map<String, Object>> topK = new ArrayList<>();
            int rank = 0;
            for (var hit : result.hits()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("chunk_id", hit.chunkId().value());
                entry.put("rank", rank++);
                entry.put("score", hit.score());
                topK.add(entry);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("query_id", query.id());
            entry.put("mode", "vector");
            entry.put("filter", "none");
            entry.put("selectivity", "-");
            entry.put("top_k", topK);
            entry.put("eligible_set", query.eligible());
            entry.put("timing_ms", timingMs);
            entry.put("timing_scope", "qdrant-bench");
            queries.add(entry);
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("run_id", "qdrant-b5-r1");
        document.put("corpus", "qdrant-mini-b5");
        document.put("queries", queries);
        Path out = Paths.get("runs", "qdrant-b5-r1-v1.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writeValueAsString(document));

        assertThat(out.toFile().exists()).isTrue();
        assertThat(queries.stream().mapToInt(q -> ((List<?>) q.get("top_k")).size()).sum())
                .isGreaterThan(0);
    }
}
