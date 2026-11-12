package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** ADR-029 immutable source revision identity and lifecycle generations. */
public record SourceRevision(
        String tenantId,
        String connectorId,
        String sourceId,
        String resourceId,
        UUID revisionId,
        long generation,
        long acceptedGeneration,
        long currentGeneration,
        String contentHash,
        UUID previousRevisionId,
        Instant createdAt) {}
