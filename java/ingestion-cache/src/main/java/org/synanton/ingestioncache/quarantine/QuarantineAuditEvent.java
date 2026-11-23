package org.synanton.ingestioncache.quarantine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable lifecycle audit event for one extraction generation. */
public record QuarantineAuditEvent(
        String tenantId,
        UUID sourceVersionId,
        UUID extractionGeneration,
        UUID eventId,
        Instant eventTime,
        String eventType,
        String actor,
        String reason,
        String detail) {

    public static QuarantineAuditEvent of(String tenantId, UUID sourceVersionId,
                                          UUID extractionGeneration, String eventType,
                                          String actor, String reason, String detail,
                                          Instant eventTime) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
        Objects.requireNonNull(extractionGeneration, "extractionGeneration");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(eventTime, "eventTime");
        return new QuarantineAuditEvent(tenantId, sourceVersionId, extractionGeneration,
                UUID.randomUUID(), eventTime, eventType, actor, reason, detail);
    }
}
