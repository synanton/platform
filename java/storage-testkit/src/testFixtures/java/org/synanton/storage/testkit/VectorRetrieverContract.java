package org.synanton.storage.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.TemporalExtension;
import org.synanton.synquest.api.VectorProjection;
import org.synanton.synquest.api.VectorRetriever;
import org.synanton.synquest.api.VectorSearchRequest;

/**
 * Adapter-agnostic contract for {@link VectorRetriever} (SYN-VECTOR-001 B3).
 * Capabilities flag, empty-store behavior, and seeded-search equivalence.
 * Seeding goes through each adapter's own writer; the contract asserts only
 * retriever-visible behavior.
 */
public abstract class VectorRetrieverContract {

    protected abstract VectorRetriever newRetriever();

    protected abstract void seed(List<VectorProjection> projections);

    protected static final TenantScope TENANT = TenantScope.of("tenant_vec");
    protected static final PolicyContext POLICY = PolicyContext.of("p", "r1");
    protected static final EmbeddingModelRef MODEL = EmbeddingModelRef.of("m", "v1", "d");
    protected static final GenerationId GEN = GenerationId.of("gen-1");

    protected static SecurityContext ctx() {
        return SecurityContext.user(TENANT, PrincipalRef.user("u-1"), POLICY);
    }

    protected static EligibilityConstraints eligibility() {
        return EligibilityConstraints.from(TENANT, List.of(PrincipalRef.user("u-1")), POLICY);
    }

    protected static VectorProjection projection(String chunk, float[] embedding, long orderingKey) {
        return new VectorProjection(
                ChunkId.of(chunk),
                DocumentId.of("doc-" + chunk),
                TENANT.tenantId(),
                embedding,
                MODEL,
                orderingKey,
                GEN);
    }

    protected static VectorSearchRequest request(float[] query, int topK) {
        return new VectorSearchRequest(
                query, Optional.of(MODEL), eligibility(), topK);
    }

    @Test
    void shouldReportVectorCapability() {
        assertThat(newRetriever().capabilities().vector()).isTrue();
    }

    @Test
    void shouldReturnEmptyOnEmptyStore() {
        var result = newRetriever()
                .search(ctx(), request(new float[] {1.0f, 0.0f}, 10))
                .toCompletableFuture()
                .join();

        assertThat(result.hits()).isEmpty();
        assertThat(result.totalEligible()).isEqualTo(0);
    }

    @Test
    void shouldFindSeededVector() {
        seed(List.of(
                projection("c1", new float[] {1.0f, 0.0f}, 1L),
                projection("c2", new float[] {0.0f, 1.0f}, 2L)));

        var result = newRetriever()
                .search(ctx(), request(new float[] {1.0f, 0.0f}, 10))
                .toCompletableFuture()
                .join();

        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).chunkId()).isEqualTo(ChunkId.of("c1"));
        assertThat(result.totalEligible()).isGreaterThanOrEqualTo(1);
    }

    protected static SearchRequest directRequest(float[] query, int topK) {
        return new SearchRequest(
                "",
                Optional.of(query),
                Optional.of(MODEL),
                SearchMode.VECTOR,
                eligibility(),
                new RelevanceFilters(Map.of()),
                TemporalExtension.empty(),
                topK,
                Double.NEGATIVE_INFINITY);
    }
}
