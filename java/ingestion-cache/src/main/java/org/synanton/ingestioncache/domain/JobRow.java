package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

public record JobRow(
    String tenantId,
    UUID jobId,
    Instant startedAt,
    Instant completedAt,
    String state,
    String source,
    String sourcePath,
    int processedCount,
    int errorCount,
    String lastError,
    int enrichedCount,
    int embeddedCount,
    int enrichmentCacheHits,
    int embeddingCacheHits,
    int enrichmentErrors,
    int embeddingErrors,
    int skippedAlreadyEmbedded
) {}
