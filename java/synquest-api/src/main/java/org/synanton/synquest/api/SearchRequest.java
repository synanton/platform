package org.synanton.synquest.api;

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
}
