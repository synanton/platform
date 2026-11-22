package org.synanton.ingestioncache.activation;

import java.time.Instant;
import java.util.UUID;

/** Cassandra-authoritative activation record per (source, generation). */
public record ActivationRecord(
        String tenantId,
        String sourceId,
        long generation,
        UUID revisionId,
        String connectorId,
        String resourceId,
        UUID previousRevisionId,
        ActivationState state,
        String contentHash,
        long graphAppliedGeneration,
        String graphAppliedState,
        long searchAppliedGeneration,
        String searchAppliedState,
        String lastError,
        boolean retryEligible,
        Instant createdAt,
        Instant updatedAt,
        Instant activatedAt) {

    public ActivationRecord {
        if (tenantId == null || tenantId.isBlank() || sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("tenant and source are required");
        }
        if (generation < 1 || revisionId == null || state == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("generation, revision, state, and timestamps are required");
        }
    }
}
