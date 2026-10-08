package org.synanton.synquest.api;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.synanton.storage.contract.EmbeddingModelRef;

/**
 * Retrieval request (proposal §10.1). Eligibility and temporal constraints are
 * pre-ranking and mandatory-shaped; relevance filters are ranking-time and optional.
 */
public record SearchRequest(
        String queryText,
        Optional<float[]> queryEmbedding,
        Optional<EmbeddingModelRef> embeddingModelRef,
        SearchMode mode,
        EligibilityConstraints eligibility,
        RelevanceFilters filters,
        TemporalExtension temporal,
        int topK,
        double minScore) {
    public SearchRequest {
        Objects.requireNonNull(queryText, "queryText");
        Objects.requireNonNull(queryEmbedding, "queryEmbedding");
        Objects.requireNonNull(embeddingModelRef, "embeddingModelRef");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(eligibility, "eligibility");
        Objects.requireNonNull(temporal, "temporal");
        filters = filters == null ? RelevanceFilters.none() : filters;
        if (queryText.isBlank() && queryEmbedding.isEmpty()) {
            throw new IllegalArgumentException("queryText or queryEmbedding is required");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SearchRequest that
                && Objects.equals(queryText, that.queryText)
                && embeddingsEqual(queryEmbedding, that.queryEmbedding)
                && Objects.equals(embeddingModelRef, that.embeddingModelRef)
                && mode == that.mode
                && Objects.equals(eligibility, that.eligibility)
                && Objects.equals(filters, that.filters)
                && Objects.equals(temporal, that.temporal)
                && topK == that.topK
                && Double.compare(minScore, that.minScore) == 0;
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(queryText);
        result = 31 * result + queryEmbedding.map(Arrays::hashCode).orElse(0);
        result = 31 * result + Objects.hashCode(embeddingModelRef);
        result = 31 * result + Objects.hashCode(mode);
        result = 31 * result + Objects.hashCode(eligibility);
        result = 31 * result + Objects.hashCode(filters);
        result = 31 * result + Objects.hashCode(temporal);
        result = 31 * result + topK;
        result = 31 * result + Double.hashCode(minScore);
        return result;
    }

    private static boolean embeddingsEqual(Optional<float[]> left, Optional<float[]> right) {
        if (left.isPresent() != right.isPresent()) {
            return false;
        }
        return left.isEmpty() || Arrays.equals(left.get(), right.get());
    }
}
