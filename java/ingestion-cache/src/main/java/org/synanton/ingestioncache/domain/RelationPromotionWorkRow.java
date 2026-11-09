package org.synanton.ingestioncache.domain;

import java.time.Instant;

/** Durable promotion work plus its claim lease (PR #4d.1). */
public record RelationPromotionWorkRow(
        RelationPromotionResultRow result,
        String claimStatus,
        String claimOwnerId,
        Instant leaseExpiresAt,
        int attemptCount,
        Instant nextAttemptAt,
        Instant claimedAt,
        String lastError,
        String failureClass) {

    public boolean claimable(Instant now) {
        return "VALID".equals(result.promotionStatus())
                && "PENDING".equals(claimStatus)
                && (nextAttemptAt == null || !nextAttemptAt.isAfter(now));
    }
}
