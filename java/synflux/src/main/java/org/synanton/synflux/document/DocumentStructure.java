package org.synanton.synflux.document;

import java.util.List;
import java.util.Objects;

/**
 * Parser-neutral structural representation of a source document (R0.2).
 *
 * <p>Offsets are UTF-16 character offsets into {@link #text()}; callers must use
 * {@link #normalizationVersion()} when interpreting them. Bounding boxes are nullable and
 * {@link GeometryStatus} is always explicit.</p>
 */
public record DocumentStructure(
    String tenantId,
    String sourceId,
    String sourceVersionId,
    String documentId,
    String mediaType,
    String normalizationVersion,
    String chunkingConfig,
    String parserName,
    String parserVersion,
    String text,
    List<Page> pages,
    List<Element> elements,
    List<Chunk> chunks
) {
    public DocumentStructure {
        requireText(tenantId, "tenantId");
        requireText(sourceId, "sourceId");
        requireText(sourceVersionId, "sourceVersionId");
        requireText(documentId, "documentId");
        requireText(mediaType, "mediaType");
        requireText(normalizationVersion, "normalizationVersion");
        requireText(chunkingConfig, "chunkingConfig");
        requireText(parserName, "parserName");
        requireText(parserVersion, "parserVersion");
        Objects.requireNonNull(text, "text");
        pages = List.copyOf(pages);
        elements = List.copyOf(elements);
        chunks = List.copyOf(chunks);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public enum ElementType {
        HEADING, PARAGRAPH, TABLE, FIGURE, LIST, CODE, OTHER
    }

    public enum GeometryStatus {
        AVAILABLE, UNAVAILABLE, NOT_APPLICABLE
    }

    /** Page geometry. Coordinate systems and units are named explicitly, never inferred. */
    public record Page(
        int pageNumber,
        float width,
        float height,
        String coordinateSystem,
        String unit
    ) {
        public Page {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("pageNumber must be >= 1");
            }
            requireText(coordinateSystem, "coordinateSystem");
            requireText(unit, "unit");
        }
    }

    public record BoundingBox(float x0, float y0, float x1, float y1) {
        public BoundingBox {
            if (x1 < x0 || y1 < y0) {
                throw new IllegalArgumentException("bounding box upper bounds must be >= lower bounds");
            }
        }
    }

    public record Element(
        String elementId,
        Integer pageNumber,
        ElementType elementType,
        int readingOrder,
        String chunkId,
        int chunkStartOffset,
        int chunkEndOffset,
        int documentStartOffset,
        int documentEndOffset,
        BoundingBox boundingBox,
        GeometryStatus geometryStatus,
        String text,
        String parserName,
        String parserVersion
    ) {
        public Element {
            requireText(elementId, "elementId");
            Objects.requireNonNull(elementType, "elementType");
            requireText(chunkId, "chunkId");
            Objects.requireNonNull(geometryStatus, "geometryStatus");
            requireText(parserName, "parserName");
            requireText(parserVersion, "parserVersion");
            Objects.requireNonNull(text, "text");
            if (readingOrder < 0 || chunkStartOffset < 0 || chunkEndOffset < chunkStartOffset
                || documentStartOffset < 0 || documentEndOffset < documentStartOffset) {
                throw new IllegalArgumentException("invalid offsets or reading order");
            }
            if (geometryStatus == GeometryStatus.AVAILABLE && boundingBox == null) {
                throw new IllegalArgumentException("AVAILABLE geometry requires a bounding box");
            }
            if (geometryStatus != GeometryStatus.AVAILABLE && boundingBox != null) {
                throw new IllegalArgumentException("bounding box requires AVAILABLE geometry");
            }
        }
    }

    /**
     * A contiguous source span. {@code sectionPath} is "" when the adapter has no section model.
     */
    public record Chunk(
        String chunkId,
        int readingOrder,
        String sectionPath,
        int documentStartOffset,
        int documentEndOffset,
        String text,
        List<String> elementIds
    ) {
        public Chunk {
            requireText(chunkId, "chunkId");
            Objects.requireNonNull(sectionPath, "sectionPath");
            Objects.requireNonNull(text, "text");
            elementIds = List.copyOf(elementIds);
            if (readingOrder < 0 || documentStartOffset < 0 || documentEndOffset < documentStartOffset) {
                throw new IllegalArgumentException("invalid offsets or reading order");
            }
        }
    }
}
