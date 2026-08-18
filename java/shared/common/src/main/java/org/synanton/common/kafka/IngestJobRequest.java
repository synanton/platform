package org.synanton.common.kafka;

import java.util.UUID;

/**
 * Kafka message payload produced to {@code ingestion_requests} and routed to
 * {@code ingestion_events}. Contains all information a synflux worker needs
 * to execute the ingestion pipeline without additional DB lookups.
 */
public record IngestJobRequest(
        String tenantId,
        UUID jobId,
        String source,
        String sourcePath,
        int priority,
        String traceId
) {}
