package org.synanton.synquest.api;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.synanton.storage.contract.EmbeddingModelRef;

/**
 * Vector-only retrieval request (SYN-VECTOR-001 B1). Carries a pre-embedded query
 * vector — embedding is the caller's responsibility (operator decision on B1 review,
 * Reading 1); the retriever never embeds and never sees text. Eligibility stays
 * mandatory and pre-ranking, mirroring {@link SearchRequest}.
 * Asymmetry is deliberate: the request's model ref is optional (callers may pass
 * pre-computed embeddings without fabricating provenance) while
 * {@link VectorProjection}'s is required (stored vectors must carry it for invalidation).
 */
public record VectorSearchRequest(
        float[] queryEmbedding,
        Optional<EmbeddingModelRef> embeddingModelRef,
        EligibilityConstraints eligibility,
        int topK) {
    public VectorSearchRequest {
        Objects.requireNonNull(queryEmbedding, "queryEmbedding");
        Objects.requireNonNull(embeddingModelRef, "embeddingModelRef");
        Objects.requireNonNull(eligibility, "eligibility");
        queryEmbedding = Arrays.copyOf(queryEmbedding, queryEmbedding.length);
        if (queryEmbedding.length == 0) {
            throw new IllegalArgumentException("queryEmbedding must not be empty");
        }
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be >= 1");
        }
    }

    public float[] queryEmbedding() {
        return Arrays.copyOf(queryEmbedding, queryEmbedding.length);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VectorSearchRequest that
                && Arrays.equals(queryEmbedding, that.queryEmbedding)
                && embeddingModelRef.equals(that.embeddingModelRef)
                && eligibility.equals(that.eligibility)
                && topK == that.topK;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(queryEmbedding);
        result = 31 * result + embeddingModelRef.hashCode();
        result = 31 * result + eligibility.hashCode();
        result = 31 * result + topK;
        return result;
    }
}
