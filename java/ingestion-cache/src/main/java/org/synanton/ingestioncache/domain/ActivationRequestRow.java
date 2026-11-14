package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** A durable ActivationRequested event waiting for search projection and activation. */
public record ActivationRequestRow(
        String tenantId,
        String connectorId,
        String sourceId,
        String resourceId,
        UUID eventId,
        UUID versionId,
        long generation,
        String contentHash,
        UUID previousRevisionId,
        String payloadJson,
        Instant requestedAt,
        Instant searchProjectedAt,
        String graphStatus,
        int attemptCount,
        String lastError,
        Instant nextAttemptAt,
        boolean deadLettered) {}
