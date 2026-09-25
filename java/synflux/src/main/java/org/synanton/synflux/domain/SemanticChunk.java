package org.synanton.synflux.domain;

import java.util.List;
import java.util.Map;

public record SemanticChunk(
    String chunkId,
    String documentId,
    int ordinal,
    ChunkType type,
    String content,
    StructuredContent structuredContent,
    List<String> sectionPath,
    String heading,
    List<String> sourceElements,
    int pageStart,
    int pageEnd,
    int tokenCount,
    boolean isPartialSection,
    Map<String, Object> metadata,
    List<String> classification,
    String sha256,
    /**
     * Hierarchy (retrieval benchmark B2, T05). {@code sectionId} is the DocumentStructureBuilder
     * section this chunk belongs to (unique per document), {@code parentSectionId} that section's
     * parent ("" at top level), {@code headingLevel} its heading level. Null / 0 for flat and
     * fallback chunks.
     */
    String sectionId,
    String parentSectionId,
    int headingLevel
) {
    public static final List<String> PUBLIC_ONLY = List.of("PUBLIC");

    public SemanticChunk(String chunkId, String documentId, int ordinal, ChunkType type, String content,
                         StructuredContent structuredContent, List<String> sectionPath, String heading,
                         List<String> sourceElements, int pageStart, int pageEnd, int tokenCount,
                         boolean isPartialSection, Map<String, Object> metadata, List<String> classification,
                         String sha256) {
        this(chunkId, documentId, ordinal, type, content, structuredContent, sectionPath, heading, sourceElements,
            pageStart, pageEnd, tokenCount, isPartialSection, metadata, classification, sha256, null, null, 0);
    }

    public SemanticChunk withHierarchy(String sectionId, String parentSectionId, int headingLevel, boolean partial) {
        return new SemanticChunk(chunkId, documentId, ordinal, type, content, structuredContent, sectionPath, heading,
            sourceElements, pageStart, pageEnd, tokenCount, partial, metadata, classification, sha256,
            sectionId, parentSectionId, headingLevel);
    }
    public enum ChunkType {
        SECTION, SUBSECTION, SUBSECTION_CHUNK, PARAGRAPH, LIST, TABLE, FIGURE,
        HEADING, IMAGE, FALLBACK, CONVERSATION_TURN, IMAGE_OCR, IMAGE_DESCRIPTION,
        VIDEO_SCENE, VIDEO_CLIP
    }

    public record StructuredContent(
        TableContent table,
        ListContent list,
        FigureContent figure
    ) {}

    public record TableContent(
        String caption,
        List<String> headers,
        List<List<String>> rows
    ) {}

    public record ListContent(List<String> items) {}

    public record FigureContent(String caption, String description) {}

    /** Backward-compatible text accessor - returns content for embedding. */
    public String text() { return content; }
}
