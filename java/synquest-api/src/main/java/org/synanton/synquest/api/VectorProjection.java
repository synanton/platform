package org.synanton.synquest.api;

import java.util.Arrays;
import java.util.Objects;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;

/**
 * Vector-path projection unit written through {@link SynquestIndexWriter} (SYN-VECTOR-001 B1).
 * Narrow by design: identity, vector payload + model provenance, and the monotonic
 * {@code orderingKey} / {@code generationId} pair (same semantics as
 * {@link ChunkProjection} — per tenant/doc/chunk commit sequence, never wall-clock).
 * No text, no metadata: lexical/content concerns stay on {@link ChunkProjection}.
 */
public record VectorProjection(
        ChunkId chunkId,
        DocumentId documentId,
        String tenantId,
        float[] embedding,
        EmbeddingModelRef embeddingModelRef,
        long orderingKey,
        GenerationId generationId) implements Projection {
    public VectorProjection {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(embedding, "embedding");
        Objects.requireNonNull(embeddingModelRef, "embeddingModelRef");
        Objects.requireNonNull(generationId, "generationId");
        embedding = Arrays.copyOf(embedding, embedding.length);
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }

    public float[] embedding() {
        return Arrays.copyOf(embedding, embedding.length);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VectorProjection that
                && chunkId.equals(that.chunkId)
                && documentId.equals(that.documentId)
                && tenantId.equals(that.tenantId)
                && Arrays.equals(embedding, that.embedding)
                && embeddingModelRef.equals(that.embeddingModelRef)
                && orderingKey == that.orderingKey
                && generationId.equals(that.generationId);
    }

    @Override
    public int hashCode() {
        int result = chunkId.hashCode();
        result = 31 * result + documentId.hashCode();
        result = 31 * result + tenantId.hashCode();
        result = 31 * result + Arrays.hashCode(embedding);
        result = 31 * result + embeddingModelRef.hashCode();
        result = 31 * result + Long.hashCode(orderingKey);
        result = 31 * result + generationId.hashCode();
        return result;
    }
}
