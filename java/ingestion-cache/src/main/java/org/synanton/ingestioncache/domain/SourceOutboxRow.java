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
    boolean published
) {}
