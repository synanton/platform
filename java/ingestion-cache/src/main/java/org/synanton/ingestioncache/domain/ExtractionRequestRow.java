package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** A pending ExtractionRequested event plus the readiness/status columns the consumer needs. */
public record ExtractionRequestRow(
        String tenantId,
        String connectorId,
        String sourceId,
        String resourceId,
        UUID eventId,
        UUID versionId,
        UUID contentRefId,
        String payloadJson,
        Instant requestedAt,
        Instant searchProjectedAt,
        String status,
        int attemptCount,
        String lastError,
        Instant nextAttemptAt,
        boolean deadLettered,
        long generation) {

    public ExtractionRequestRow(String tenantId, String connectorId, String sourceId,
                                String resourceId, UUID eventId, UUID versionId,
                                UUID contentRefId, String payloadJson, Instant requestedAt,
                                Instant searchProjectedAt, String status, int attemptCount,
                                String lastError, Instant nextAttemptAt, boolean deadLettered) {
        this(tenantId, connectorId, sourceId, resourceId, eventId, versionId, contentRefId,
                payloadJson, requestedAt, searchProjectedAt, status, attemptCount, lastError,
                nextAttemptAt, deadLettered, 0);
    }
}
