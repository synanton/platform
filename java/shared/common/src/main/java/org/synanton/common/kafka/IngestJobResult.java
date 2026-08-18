package org.synanton.common.kafka;

import java.util.UUID;

/**
 * Kafka message payload produced to {@code ingestion_completed} by a synflux
 * worker when a job finishes (successfully or with failure).
 */
public record IngestJobResult(
        UUID jobId,
        String tenantId,
        String state,
        int entityCount,
        int embeddingCount,
        long durationMs,
        String errorMessage
) {}
