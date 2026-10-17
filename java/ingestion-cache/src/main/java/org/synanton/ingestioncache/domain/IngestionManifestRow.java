package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable terminal manifest descriptor; entries are addressed by entriesRef. */
public record IngestionManifestRow(
    String tenantId,
    UUID runId,
    UUID manifestId,
    Instant generatedAt,
    int entryCount,
    String entriesRef
) {}
