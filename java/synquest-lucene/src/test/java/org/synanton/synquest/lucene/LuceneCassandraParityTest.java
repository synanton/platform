package org.synanton.synquest.lucene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.storage.contract.ChunkId;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.TemporalExtension;
import org.synanton.synquest.api.VectorProjection;
import org.synanton.synquest.cassandra.CassandraSynquestEngine;

/**
 * SYN-VECTOR-001 B3.3 acceptance: standalone Lucene produces the same
 * vector-only numbers as Cassandra+Lucene on identical queries. Same vectors,
 * same cosine KNN, same ordering — the store explains nothing about the
 * vector leg (R3 attribution resolved).
 */
class LuceneCassandraParityTest {

    @TempDir
    private Path root;

    private static final EmbeddingModelRef MODEL = new EmbeddingModelRef("test", "v1", "d");
    private static final GenerationId GEN = new GenerationId("g1");

    private static final float[][] VECTORS = {
        {1.0f, 0.0f},
        {0.9f, 0.1f},
        {0.0f, 1.0f},
        {0.1f, 0.9f},
        {0.5f, 0.5f},
    };

    @Test
    void shouldMatchCassandraLuceneVectorOrdering() {
        String tenant = "t-parity";
        CassandraSynquestEngine cassandra =
                new CassandraSynquestEngine(root.resolve("cass-" + System.nanoTime()));
        LuceneStandaloneVectorRetriever standalone = new LuceneStandaloneVectorRetriever(
                root.resolve("luc-" + System.nanoTime()), 2);

        for (int i = 0; i < VECTORS.length; i++) {
            String id = "c" + i;
            cassandra
                    .upsert(List.of(
                            new ChunkProjection(
                                    ChunkId.of(id),
                                    new DocumentId("d-" + id),
                                    tenant,
                                    "text " + id,
                                    Map.of(),
                                    VECTORS[i],
                                    MODEL,
                                    i + 1L,
                                    GEN)))
                    .toCompletableFuture()
                    .join();
            standalone
                    .upsert(List.of(
                            new VectorProjection(
                                    ChunkId.of(id),
                                    new DocumentId("d-" + id),
                                    tenant,
                                    VECTORS[i],
                                    MODEL,
                                    i + 1L,
                                    GEN)))
                    .toCompletableFuture()
                    .join();
        }

        SecurityContext context = SecurityContext.user(
                new TenantScope(tenant), new PrincipalRef("user", "u1"), new PolicyContext("p", "1"));
        EligibilityConstraints eligibility = EligibilityConstraints.from(
                new TenantScope(tenant),
                List.of(new PrincipalRef("user", "u1")),
                new PolicyContext("p", "1"));
        SearchRequest direct = new SearchRequest(
                "",
                Optional.of(new float[] {1.0f, 0.0f}),
                Optional.of(MODEL),
                SearchMode.VECTOR,
                eligibility,
                RelevanceFilters.none(),
                TemporalExtension.empty(),
                5,
                Double.NEGATIVE_INFINITY);

        var expected = cassandra.search(context, direct).toCompletableFuture().join();
        var actual = standalone
                .search(
                        context,
                        new org.synanton.synquest.api.VectorSearchRequest(
                                new float[] {1.0f, 0.0f}, Optional.of(MODEL), eligibility, 5))
                .toCompletableFuture()
                .join();

        assertThat(actual.hits()).hasSameSizeAs(expected.hits());
        for (int i = 0; i < expected.hits().size(); i++) {
            assertThat(actual.hits().get(i).chunkId())
                    .isEqualTo(expected.hits().get(i).chunkId());
            assertThat(actual.hits().get(i).score())
                    .isCloseTo(expected.hits().get(i).score(), within(1e-6));
        }
    }
}
