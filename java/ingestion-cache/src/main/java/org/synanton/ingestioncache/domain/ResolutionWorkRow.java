package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable resolution work item and its claim lease (PR #4d.1). It is created
 * PENDING when the extracted mention graph commits; the decision columns are
 * filled only by the resolution consumer.
 */
public record ResolutionWorkRow(
        String tenantId,
        UUID sourceVersionId,
        String mentionId,
        String connectorId,
        String sourceId,
        String resourceId,
        UUID contentRefId,
        int chunkOrdinal,
        int chunkOffsetStart,
        int chunkOffsetEnd,
        int documentOffsetStart,
        int documentOffsetEnd,
        String surfaceForm,
        String proposedType,
        String claimStatus,
        String claimOwnerId,
        Instant leaseExpiresAt,
        int attemptCount,
        Instant nextAttemptAt,
        Instant claimedAt,
        Instant workCreatedAt,
        String lastError,
        String failureClass,
        String outcome) {

    public boolean claimable(Instant now) {
        return "PENDING".equals(claimStatus)
                && (nextAttemptAt == null || !nextAttemptAt.isAfter(now));
    }
}
