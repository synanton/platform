package org.synanton.synvault.api;

import java.util.Optional;

/**
 * Options for {@link SynvaultStore#putDocumentRevision}.
 *
 * @param expectedRevision when present, the commit is rejected with
 *                         {@code CONFLICT} unless the stored revision matches
 *                         (optimistic concurrency on the internal storage revision)
 */
public record RevisionWriteOptions(Optional<Long> expectedRevision) {
    public RevisionWriteOptions {
        java.util.Objects.requireNonNull(expectedRevision, "expectedRevision");
    }

    public static RevisionWriteOptions unconditional() {
        return new RevisionWriteOptions(Optional.empty());
    }

    public static RevisionWriteOptions expectRevision(long revision) {
        return new RevisionWriteOptions(Optional.of(revision));
    }
}
