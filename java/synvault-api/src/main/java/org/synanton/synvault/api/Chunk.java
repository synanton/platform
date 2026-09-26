package org.synanton.synvault.api;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Semantic content chunk belonging to a {@link Document}.
 *
 * @param id          chunk identity
 * @param documentId  owning document
 * @param ordinal     stable position within the document (pagination order)
 * @param text        chunk text
 * @param tokenCount  token count
 * @param metadata    chunk-level metadata
 * @param embedding   optional embedding vector (defensive copy; may be null)
 */
public record Chunk(
        org.synanton.storage.contract.ChunkId id,
        org.synanton.storage.contract.DocumentId documentId,
        int ordinal,
        String text,
        int tokenCount,
        Map<String, String> metadata,
        float[] embedding) {
    public Chunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(text, "text");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        embedding = embedding == null ? null : Arrays.copyOf(embedding, embedding.length);
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be >= 0");
        }
    }

    public float[] embedding() {
        return embedding == null ? null : Arrays.copyOf(embedding, embedding.length);
    }
}
