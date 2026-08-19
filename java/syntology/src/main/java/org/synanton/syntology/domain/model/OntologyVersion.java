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
        Instant createdAt
) {
}
