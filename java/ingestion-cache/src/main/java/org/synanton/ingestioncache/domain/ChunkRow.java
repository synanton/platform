package org.synanton.ingestioncache.domain;

import java.util.UUID;

public record ChunkRow(
    String tenantId,
    UUID contentRefId,
    int chunkOrdinal,
    String chunkText,
    String chunkSha256,
    int pageStart,
    int pageEnd,
    String sectionPath,
    String chunkType,
    String heading
) {
    public ChunkRow(
            String tenantId,
            UUID contentRefId,
            int chunkOrdinal,
            String chunkText,
            String chunkSha256) {
        this(tenantId, contentRefId, chunkOrdinal, chunkText, chunkSha256, -1, -1, "", "", "");
    }
}
