package org.synanton.synquest.qdrant;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;

import java.net.InetSocketAddress;
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
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.HttpLlmClient;
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
 * F-2 Qdrant hybrid leg with REAL vectors: 81 demo-corpus chunk embeddings
 * (768d, synanton-bge-base-embedding) backfilled from the ingestion cache into
 * a fresh HNSW collection; the 3 F-3 queries embedded live via TEI.
 * Requires bench Qdrant (:6334), bench Cassandra (:9042), TEI tunnel (:30800).
 * Artifact to runs/; recall scored offline against queries-f1-dense.json gold.
 */
class QdrantF2HybridLegRun {

    private static final EmbeddingModelRef MODEL =
            EmbeddingModelRef.of("synanton-bge-base-embedding", "bge-base-en-v1.5", "tei-1.9");
    private static final GenerationId GEN = new GenerationId("f2-gen-1");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TENANT = "demo";

    private record TextQuery(String id, String text) {}

    private static final List<TextQuery> QUERIES = List.of(
            new TextQuery("f1-q1", "company profile industrial manufacturing"),
            new TextQuery("f1-q2", "supplier precision metal components automotive"),
            new TextQuery("f1-q3", "compliance report quarterly logistics network"));

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
    void shouldProduceMilvusF2Artifact() throws Exception {
        String cassandraHost = System.getenv().getOrDefault("BENCH_CASSANDRA_HOST", "localhost");
        int cassandraPort = Integer.parseInt(System.getenv().getOrDefault("BENCH_CASSANDRA_PORT", "9042"));
        String teiBase = System.getenv().getOrDefault("BENCH_TEI_BASE_URL", "http://localhost:30800/v1");

        String collection = "t_f2_qdrant_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        QdrantVectorRetriever adapter = new QdrantVectorRetriever(client(), collection, 768);

        List<VectorProjection> rows = new ArrayList<>();
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
                List<EmbeddingRow> embeddings =
                        cache.listEmbeddings(TENANT, manifest.contentRefId()).stream()
                                .filter(e -> MODEL.modelId().equals(e.modelId()))
                                .toList();
                for (EmbeddingRow e : embeddings) {
                    assertThat(e.embeddingDim()).isEqualTo(768);
                    rows.add(new VectorProjection(
                            ChunkId.of(e.contentRefId().toString() + "#" + e.chunkOrdinal()),
                            DocumentId.of("d-" + e.contentRefId()),
                            TENANT,
                            e.embedding(),
                            MODEL,
                            ordering++,
                            GEN));
                }
            }
        }
        assertThat(rows).hasSizeGreaterThanOrEqualTo(81);
        adapter.upsert(rows).toCompletableFuture().join();

        HttpLlmClient tei = new HttpLlmClient(teiBase, 3);
        List<Map<String, Object>> queries = new ArrayList<>();
        for (TextQuery query : QUERIES) {
            float[] vector = tei.embed(new EmbedRequest(MODEL.modelId(), List.of(query.text())))
                    .embeddings().get(0);
            assertThat(vector).hasSize(768);
            SecurityContext context = SecurityContext.user(
                    new TenantScope(TENANT), new PrincipalRef("user", "u1"), new PolicyContext("p", "1"));
            EligibilityConstraints eligibility = EligibilityConstraints.from(
                    new TenantScope(TENANT), List.of(new PrincipalRef("user", "u1")), new PolicyContext("p", "1"));
            long start = System.nanoTime();
            VectorSearchResult result = adapter
                    .search(context, new VectorSearchRequest(vector, Optional.of(MODEL), eligibility, 10))
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
            entry.put("timing_ms", timingMs);
            entry.put("timing_scope", "qdrant-f2");
            queries.add(entry);
        }

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("run_id", "qdrant-f2-r1");
        document.put("corpus", "demo-frozen-v1");
        document.put("embedding_model", MODEL.modelId());
        document.put("embedding_dim", 768);
        document.put("queries", queries);
        Path out = Paths.get("runs", "qdrant-f2-r1-v1.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writeValueAsString(document));

        assertThat(out.toFile().exists()).isTrue();
        assertThat(queries.stream().mapToInt(q -> ((List<?>) q.get("top_k")).size()).sum())
                .isGreaterThan(0);
    }
}
