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
    String sha256
) {
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
