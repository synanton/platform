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
    int headingLevel,
    /** Source version this chunk derives from — the §13 provenance chain Source → Chunk. */
    UUID sourceVersionId,
    /** Processing run that produced this chunk (Design 1.25 §12). */
    UUID processingRunId
) {
    public static final List<String> PUBLIC_ONLY = List.of("PUBLIC");

    public ChunkRow(
            String tenantId, UUID contentRefId, int chunkOrdinal, String chunkText, String chunkSha256,
            int pageStart, int pageEnd, String sectionPath, String chunkType, String heading,
            String sourceElementsJson, int tokenCount, String structuredContentJson, boolean isPartialSection,
            List<String> classification) {
        this(tenantId, contentRefId, chunkOrdinal, chunkText, chunkSha256, pageStart, pageEnd, sectionPath, chunkType,
            heading, sourceElementsJson, tokenCount, structuredContentJson, isPartialSection, classification, "", "", 0,
            null, null);
    }

    public ChunkRow(
            String tenantId, UUID contentRefId, int chunkOrdinal, String chunkText, String chunkSha256,
            int pageStart, int pageEnd, String sectionPath, String chunkType, String heading,
            String sourceElementsJson, int tokenCount, String structuredContentJson, boolean isPartialSection,
            List<String> classification, String sectionId, String parentSectionId, int headingLevel) {
        this(tenantId, contentRefId, chunkOrdinal, chunkText, chunkSha256, pageStart, pageEnd, sectionPath, chunkType,
            heading, sourceElementsJson, tokenCount, structuredContentJson, isPartialSection, classification,
            sectionId, parentSectionId, headingLevel, null, null);
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

    public ChunkRow withoutProvenanceLinks() {
        return new ChunkRow(tenantId, contentRefId, chunkOrdinal, chunkText, chunkSha256,
            pageStart, pageEnd, sectionPath, chunkType, heading, sourceElementsJson, tokenCount,
            structuredContentJson, isPartialSection, classification, sectionId, parentSectionId,
            headingLevel, null, null);
    }
}
