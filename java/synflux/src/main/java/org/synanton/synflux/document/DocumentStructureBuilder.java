package org.synanton.synflux.document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Assigns deterministic identities and UTF-16 offsets for adapter output. */
public final class DocumentStructureBuilder {
    public record Block(
        Integer pageNumber,
        DocumentStructure.ElementType elementType,
        String text,
        DocumentStructure.BoundingBox boundingBox,
        DocumentStructure.GeometryStatus geometryStatus,
        String chunkKey,
        String sectionPath
    ) {
        public Block {
            Objects.requireNonNull(elementType, "elementType");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(geometryStatus, "geometryStatus");
            Objects.requireNonNull(chunkKey, "chunkKey");
            Objects.requireNonNull(sectionPath, "sectionPath");
        }
    }

    private final SourceDocument source;
    private final String normalizationVersion;
    private final String chunkingConfig;
    private final String parserName;
    private final String parserVersion;
    private final List<DocumentStructure.Page> pages;
    private final List<Block> blocks = new ArrayList<>();

    public DocumentStructureBuilder(SourceDocument source, String normalizationVersion, String chunkingConfig,
                             String parserName, String parserVersion, List<DocumentStructure.Page> pages) {
        this.source = source;
        this.normalizationVersion = normalizationVersion;
        this.chunkingConfig = chunkingConfig;
        this.parserName = parserName;
        this.parserVersion = parserVersion;
        this.pages = List.copyOf(pages);
    }

    public void add(Block block) {
        blocks.add(block);
    }

    public DocumentStructure build() {
        StringBuilder documentText = new StringBuilder();
        List<DocumentStructure.Element> elements = new ArrayList<>(blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (i > 0) {
                documentText.append('\n');
            }
            int start = documentText.length();
            documentText.append(block.text());
            int end = documentText.length();
            elements.add(null); // replaced once chunk identity is known
            elements.set(i, buildElement(block, i, start, end));
        }

        List<DocumentStructure.Chunk> chunks = new ArrayList<>();
        int chunkStart = 0;
        while (chunkStart < elements.size()) {
            int chunkEnd = chunkStart + 1;
            while (chunkEnd < elements.size()
                && blocks.get(chunkEnd).chunkKey().equals(blocks.get(chunkStart).chunkKey())) {
                chunkEnd++;
            }
            int ordinal = chunks.size();
            String chunkId = stableId("chunk", ordinal);
            int documentStart = elements.get(chunkStart).documentStartOffset();
            int documentEnd = elements.get(chunkEnd - 1).documentEndOffset();
            List<String> elementIds = new ArrayList<>(chunkEnd - chunkStart);
            for (int i = chunkStart; i < chunkEnd; i++) {
                DocumentStructure.Element e = elements.get(i);
                elementIds.add(e.elementId());
                elements.set(i, new DocumentStructure.Element(
                    e.elementId(), e.pageNumber(), e.elementType(), e.readingOrder(), chunkId,
                    e.documentStartOffset() - documentStart, e.documentEndOffset() - documentStart,
                    e.documentStartOffset(), e.documentEndOffset(), e.boundingBox(), e.geometryStatus(),
                    e.text(), e.parserName(), e.parserVersion()));
            }
            chunks.add(new DocumentStructure.Chunk(
                chunkId, ordinal, blocks.get(chunkStart).sectionPath(), documentStart, documentEnd,
                documentText.substring(documentStart, documentEnd), elementIds));
            chunkStart = chunkEnd;
        }

        return new DocumentStructure(
            source.tenantId(), source.sourceId(), source.sourceVersionId(), source.documentId(),
            source.mediaType(), normalizationVersion, chunkingConfig, parserName, parserVersion,
            documentText.toString(), pages, elements, chunks);
    }

    private DocumentStructure.Element buildElement(Block block, int readingOrder, int start, int end) {
        String elementId = stableId("element", readingOrder);
        return new DocumentStructure.Element(
            elementId, block.pageNumber(), block.elementType(), readingOrder, "pending",
            start, end, start, end, block.boundingBox(), block.geometryStatus(), block.text(),
            parserName, parserVersion);
    }

    private String stableId(String kind, int ordinal) {
        String value = source.documentId() + "|" + kind + "|" + ordinal;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
