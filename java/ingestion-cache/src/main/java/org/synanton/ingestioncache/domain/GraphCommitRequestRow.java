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
        boolean extractionDeadLettered,
        long generation) {

    public GraphCommitRequestRow(
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
            boolean extractionDeadLettered) {
        this(tenantId, connectorId, sourceId, resourceId, eventId, versionId, contentRefId,
                payloadJson, requestedAt, searchProjectedAt, extractionStatus, graphStatus,
                graphAttemptCount, graphNextAttemptAt, extractionAttemptCount, extractionLastError,
                extractionNextAttemptAt, extractionDeadLettered, 0L);
    }
}
