package org.synanton.ingestioncache.domain;

import java.util.List;
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
    boolean isPartialSection,
    List<String> classification,
    /** Document section of the chunk (B2/T05); "" when the chunk has no section (flat/fallback). */
    String sectionId,
    String parentSectionId,
    int headingLevel
) {
    public static final List<String> PUBLIC_ONLY = List.of("PUBLIC");

    public ChunkRow(
            String tenantId, UUID contentRefId, int chunkOrdinal, String chunkText, String chunkSha256,
            int pageStart, int pageEnd, String sectionPath, String chunkType, String heading,
            String sourceElementsJson, int tokenCount, String structuredContentJson, boolean isPartialSection,
            List<String> classification) {
        this(tenantId, contentRefId, chunkOrdinal, chunkText, chunkSha256, pageStart, pageEnd, sectionPath, chunkType,
            heading, sourceElementsJson, tokenCount, structuredContentJson, isPartialSection, classification, "", "", 0);
    }

    public ChunkRow(
            String tenantId,
            UUID contentRefId,
            int chunkOrdinal,
            String chunkText,
            String chunkSha256) {
        this(tenantId, contentRefId, chunkOrdinal, chunkText, chunkSha256,
            -1, -1, "", "", "", "[]", 0, "", false, PUBLIC_ONLY);
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
            "[]", 0, "", false, PUBLIC_ONLY);
    }
}
