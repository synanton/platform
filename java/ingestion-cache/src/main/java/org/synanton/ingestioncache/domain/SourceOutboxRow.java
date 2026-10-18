package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable source lifecycle event awaiting publication. */
public record SourceOutboxRow(
    String tenantId,
    String connectorId,
    String sourceId,
    String resourceId,
    UUID eventId,
    String eventType,
    UUID sourceVersionId,
    UUID tombstoneId,
    String payloadJson,
    Instant createdAt,
    boolean published,
    Instant publishedAt,
    int attemptCount,
    String lastError,
    Instant nextAttemptAt,
    boolean deadLettered
) {
    public SourceOutboxRow(
            String tenantId,
            String connectorId,
            String sourceId,
            String resourceId,
            UUID eventId,
            String eventType,
            UUID sourceVersionId,
            UUID tombstoneId,
            String payloadJson,
            Instant createdAt,
            boolean published) {
        this(tenantId, connectorId, sourceId, resourceId, eventId, eventType, sourceVersionId,
            tombstoneId, payloadJson, createdAt, published, null, 0, null, null, false);
    }
}
