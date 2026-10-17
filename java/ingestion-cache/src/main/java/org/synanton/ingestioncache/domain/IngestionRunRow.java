package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Durable ingestion-specific status, linked by ID to the public Operation resource. */
public record IngestionRunRow(
    String tenantId,
    UUID runId,
    UUID operationId,
    String sourceId,
    String status,
    Instant startedAt,
    Instant completedAt,
    SyncResultRow result,
    String errorRef
) {}
