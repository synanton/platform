package org.synanton.synvault.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Mandatory provenance for derived state (Architecture 1.0 invariant 12).
 * Links a chunk back to the Content Cache artifact and the Ingestion 1.28
 * {@code SourceVersion} it was derived from.
 *
 * @param chunkId            chunk this record describes
 * @param extractor          extractor identity (name)
 * @param sourceVersionId    Ingestion 1.28 SourceVersion reference
 * @param embeddingModelRef  model behind the chunk embedding, if any
 * @param page               source page
 * @param startOffset        character offsets within the source
 * @param endOffset          character offsets within the source
 */
public record ProvenanceRecord(
        org.synanton.storage.contract.ChunkId chunkId,
        String extractor,
        org.synanton.storage.contract.SourceVersionId sourceVersionId,
        Optional<org.synanton.storage.contract.EmbeddingModelRef> embeddingModelRef,
        int page,
        int startOffset,
        int endOffset) {
    public ProvenanceRecord {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(extractor, "extractor");
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
        Objects.requireNonNull(embeddingModelRef, "embeddingModelRef");
        if (extractor.isBlank()) {
            throw new IllegalArgumentException("extractor must not be blank");
        }
    }
}
