package org.synanton.synquest.api;

import java.util.Map;
import java.util.Objects;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;

/**
 * Single ranked hit. The score is backend-specific; cross-adapter comparison uses
 * semantic tolerances (YDB-POC-019), never byte-for-byte parity.
 */
public record SearchHit(
        ChunkId chunkId, DocumentId documentId, double score, String text, Map<String, String> metadata) {
    public SearchHit {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(text, "text");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
