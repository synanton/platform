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
    String heading,
    String sourceElementsJson,
    int tokenCount,
    String structuredContentJson,
    boolean isPartialSection
) {
    public ChunkRow(
            String tenantId,
            UUID contentRefId,
            int chunkOrdinal,
            String chunkText,
            String chunkSha256) {
        this(tenantId, contentRefId, chunkOrdinal, chunkText, chunkSha256,
            -1, -1, "", "", "", "[]", 0, "", false);
    }

    public ChunkRow(
            String tenantId,
            UUID contentRefId,
            int chunkOrdinal,
            String chunkText,
            String chunkSha256,
            int pageStart,
            int pageEnd,
            String sectionPath,
            String chunkType,
            String heading) {
        this(tenantId, contentRefId, chunkOrdinal, chunkText, chunkSha256,
            pageStart, pageEnd, sectionPath, chunkType, heading,
            "[]", 0, "", false);
    }
}
