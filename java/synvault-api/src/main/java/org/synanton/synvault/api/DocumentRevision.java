package org.synanton.synvault.api;

import java.util.List;
import java.util.Objects;

/**
 * The atomicity unit of {@link SynvaultStore#putDocumentRevision} (proposal §9.1).
 * The document, its chunks, provenance, and the durable publication record commit
 * atomically when the backend supports the required transaction semantics.
 *
 * <p>Provenance is mandatory: an empty list is rejected by the port contract and by
 * every adapter (invariant 12).
 */
public record DocumentRevision(
        Document document,
        List<Chunk> chunks,
        List<ProvenanceRecord> provenance,
        RevisionMetadata metadata,
        PublicationIntent publication) {
    public DocumentRevision {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(publication, "publication");
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
        if (provenance.isEmpty()) {
            throw new IllegalArgumentException("provenance is mandatory and must not be empty");
        }
    }
}
