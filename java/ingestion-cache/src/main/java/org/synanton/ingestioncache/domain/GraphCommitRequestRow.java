package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** A KNOWLEDGE_READY version whose graph commit is pending or failed. */
public record GraphCommitRequestRow(
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
        String extractionStatus,
        String graphStatus,
        int graphAttemptCount,
        Instant graphNextAttemptAt,
        int extractionAttemptCount,
        String extractionLastError,
        Instant extractionNextAttemptAt,
        boolean extractionDeadLettered) {}
