package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

public record AnalysisRow(
    String tenantId,
    UUID contentRefId,
    int chunkOrdinal,
    int passNumber,
    String modelId,
    String promptVersion,
    String analysisJson,
    String inputSha256,
    Instant createdAt
) {}
