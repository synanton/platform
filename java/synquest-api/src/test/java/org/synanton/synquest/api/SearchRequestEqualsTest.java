package org.synanton.synquest.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.TenantScope;

/**
 * Value equality for {@link SearchRequest}: embeddings compare by content, not
 * reference, so wrapper delegation tests can assert whole captured requests
 * (SYN-VECTOR-001 B1.3). Pre-checked: no Map/Set/cache keys on SearchRequest
 * in main — behavior-neutral.
 */
class SearchRequestEqualsTest {

    private static SearchRequest request(float[] embedding, double minScore) {
        return new SearchRequest(
                "",
                Optional.of(embedding),
                Optional.of(new EmbeddingModelRef("bge-base", "v1", "abc123")),
                SearchMode.VECTOR,
                new EligibilityConstraints(
                        new TenantScope("demo"),
                        List.of(new PrincipalRef("user", "u1")),
                        new PolicyContext("p", "1"),
                        true),
                new RelevanceFilters(Map.of()),
                TemporalExtension.empty(),
                10,
                minScore);
    }

    @Test
    void shouldTreatEqualContentWithDistinctArraysAsEqual() {
        SearchRequest first = request(new float[] {0.1f, 0.2f}, 0.0);
        SearchRequest second = request(new float[] {0.1f, 0.2f}, 0.0);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void shouldDistinguishDifferentEmbeddings() {
        SearchRequest first = request(new float[] {0.1f, 0.2f}, 0.0);
        SearchRequest second = request(new float[] {0.1f, 0.9f}, 0.0);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldDistinguishDifferentMinScore() {
        SearchRequest first = request(new float[] {0.1f, 0.2f}, 0.0);
        SearchRequest second =
                request(new float[] {0.1f, 0.2f}, Double.NEGATIVE_INFINITY);

        assertThat(first).isNotEqualTo(second);
    }
}
