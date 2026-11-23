package org.synanton.ingestioncache.compliance;

import java.time.Instant;
import java.util.UUID;

/** Durable audit for the Phase 2a hard-deletion scope. */
public record ComplianceDeletionEvent(
        String tenantId,
        UUID sourceVersionId,
        Instant eventTime,
        UUID eventId,
        String eventType,
        String recordScope,
        int deletedCount,
        String actor,
        String reason,
        String detail) {}
