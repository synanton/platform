package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

public record EmbeddingRow(
    String tenantId,
    UUID contentRefId,
    int chunkOrdinal,
    String modelId,
    String chunkSha256,
    float[] embedding,  // decompressed
    int embeddingDim,
    Instant createdAt
) {}
