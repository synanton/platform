package org.synanton.synvault.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical knowledge document owned by Knowledge 1.25 and persisted through
 * {@link SynvaultStore}. Within-port ownership only — source-version authority
 * stays with Ingestion 1.28 (see {@code sourceVersionId} references in provenance).
 *
 * @param id              document identity (opaque to backends)
 * @param title           human-readable title
 * @param sourceUri       reference into Content Cache 1.26 or the external source — never a duplicate
 * @param metadata        document-level metadata (non-derived attributes)
 * @param storageRevision internal optimistic-concurrency number, not the platform semantic version
 * @param createdAt       persistence timestamps
 * @param updatedAt       persistence timestamps
 */
public record Document(
        org.synanton.storage.contract.DocumentId id,
        String title,
        String sourceUri,
        Map<String, String> metadata,
        long storageRevision,
        Instant createdAt,
        Instant updatedAt) {
    public Document {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(sourceUri, "sourceUri");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
