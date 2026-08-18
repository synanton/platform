package org.synanton.ingestioncache.domain;

import java.util.UUID;

public record ChunkRow(
    String tenantId,
    UUID contentRefId,
    int chunkOrdinal,
    String chunkText,
    String chunkSha256
) {}
