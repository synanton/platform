package org.synanton.syntology.domain.model;

import java.time.Instant;
import java.util.UUID;

public record OntologyVersion(
        UUID versionId,
        String tenantId,
        String version,
        String label,
        String description,
        String graphUri,
        String status,
        Instant createdAt,
        String releaseId,
        String releaseDigest
) {
    /** Backward-compatible constructor for existing callers/tests. */
    public OntologyVersion(UUID versionId, String tenantId, String version, String label,
                           String description, String graphUri, String status, Instant createdAt) {
        this(versionId, tenantId, version, label, description, graphUri, status, createdAt,
                versionId == null ? null : versionId.toString(), null);
    }
}
