package org.synanton.synvault.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Revision bookkeeping: the internal storage revision plus authoring identity.
 * {@code storageRevision} is persistence-level optimistic concurrency — never the
 * platform semantic version (owned by Ingestion 1.28 / Design 1.34).
 */
public record RevisionMetadata(
        long storageRevision, org.synanton.storage.contract.PrincipalRef author, Instant observedAt) {
    public RevisionMetadata {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(observedAt, "observedAt");
    }
}
