package org.synanton.synquest.api;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;

/**
 * Derived projection unit written through {@link SynquestIndexWriter} (proposal §10.3).
 * Carries the monotonic {@code orderingKey} (per tenant/doc/chunk, from the Synvault
 * commit sequence — never wall-clock) and the {@code generationId} so regression
 * prevention is testable at the port, not only inside an adapter (invariants 35–36).
 */
public record ChunkProjection(
        ChunkId chunkId,
        DocumentId documentId,
        String tenantId,
        String text,
        Map<String, String> metadata,
        float[] embedding,
        EmbeddingModelRef embeddingModelRef,
        long orderingKey,
        GenerationId generationId) {
    public ChunkProjection {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(embeddingModelRef, "embeddingModelRef");
        Objects.requireNonNull(generationId, "generationId");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        embedding = embedding == null ? null : Arrays.copyOf(embedding, embedding.length);
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }

    public float[] embedding() {
        return embedding == null ? null : Arrays.copyOf(embedding, embedding.length);
    }
}
