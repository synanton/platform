package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** One final disposition per source resource discovered by an ingestion run. */
public record IngestionManifestEntryRow(
    String tenantId,
    UUID runId,
    String connectorId,
    String sourceId,
    String resourceId,
    String result,
    UUID sourceVersionId,
    String errorRef,
    Instant observedAt
) {}
