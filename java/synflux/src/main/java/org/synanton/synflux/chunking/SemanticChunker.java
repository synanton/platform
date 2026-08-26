package org.synanton.synflux.chunking;

import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.domain.SemanticChunk.ChunkType;
import org.synanton.synflux.domain.SemanticChunk.FigureContent;
import org.synanton.synflux.domain.SemanticChunk.ListContent;
import org.synanton.synflux.domain.SemanticChunk.StructuredContent;
import org.synanton.synflux.domain.SemanticChunk.TableContent;
import synanton.extraction.v1.DocumentElement;
import synanton.extraction.v1.DocumentElementType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts a section tree into a flat list of {@link SemanticChunk}s.
 */
public class SemanticChunker {

    public List<SemanticChunk> chunk(
            List<SectionNode> sections,
            String documentId,
            ChunkerConfig config) {

        List<SemanticChunk> result = new ArrayList<>();
        AtomicInteger ordinal = new AtomicInteger();
        for (SectionNode section : sections) {
            chunkSection(section, documentId, config, result, ordinal);
        }
        return Collections.unmodifiableList(result);
    }

    private void chunkSection(
            SectionNode section,
            String documentId,
            ChunkerConfig config,
            List<SemanticChunk> out,
            AtomicInteger ordinal) {

        List<DocumentElement> elements = section.elements();
        List<DocumentElement> batch = new ArrayList<>();
        int batchTokens = 0;

        String headingPrefix = (config.includeHeadingInContent() && section.heading() != null)
            ? section.heading() + "\n\n"
            : "";
        if (!headingPrefix.isEmpty()) {
            batchTokens += estimateTokens(headingPrefix);
        }

        for (int i = 0; i < elements.size(); i++) {
            DocumentElement el = elements.get(i);

            if (el.getType() == DocumentElementType.ELEMENT_TABLE && config.keepTableAtomic()) {
                flushBatch(batch, headingPrefix, section, documentId, config, out, ordinal);
                batch.clear();
                batchTokens = 0;
                headingPrefix = "";
                out.add(buildTableChunk(el, section, documentId, ordinal.getAndIncrement(), config));
                continue;
            }

            if (config.keepListAtomic() && isListElement(el)) {
                flushBatch(batch, headingPrefix, section, documentId, config, out, ordinal);
                batch.clear();
                batchTokens = 0;
                headingPrefix = "";
                List<DocumentElement> listRun = collectListRun(elements, i);
                emitListChunks(listRun, section, documentId, config, out, ordinal);
                i += listRun.size() - 1;
                continue;
            }

            if (config.keepFigureWithCaption() && el.getType() == DocumentElementType.ELEMENT_IMAGE) {
                flushBatch(batch, headingPrefix, section, documentId, config, out, ordinal);
                batch.clear();
                batchTokens = 0;
                headingPrefix = "";
                DocumentElement caption = peekCaption(elements, i + 1);
                out.add(buildFigureChunk(el, caption, section, documentId, ordinal.getAndIncrement(), config));
                if (caption != null) {
                    i++;
                }
                continue;
            }

            int elTokens = estimateTokens(el.getText());
            if (!batch.isEmpty() && batchTokens + elTokens > config.maxTokensPerChunk()) {
                flushBatch(batch, headingPrefix, section, documentId, config, out, ordinal);
                batch.clear();
                batchTokens = 0;
                headingPrefix = "";
            }
            batch.add(el);
            batchTokens += elTokens;
        }

        flushBatch(batch, headingPrefix, section, documentId, config, out, ordinal);

        for (SectionNode child : section.children()) {
            chunkSection(child, documentId, config, out, ordinal);
        }
    }

    private void flushBatch(
            List<DocumentElement> batch,
            String headingPrefix,
            SectionNode section,
            String documentId,
            ChunkerConfig config,
            List<SemanticChunk> out,
            AtomicInteger ordinal) {
        if (batch.isEmpty()) {
            return;
        }
        out.add(buildBatchChunk(batch, headingPrefix, section, documentId,
            ordinal.getAndIncrement(), config, false));
    }

    private void emitListChunks(
            List<DocumentElement> listRun,
            SectionNode section,
            String documentId,
            ChunkerConfig config,
            List<SemanticChunk> out,
            AtomicInteger ordinal) {

        int start = 0;
        while (start < listRun.size()) {
            List<DocumentElement> slice = new ArrayList<>();
            int tokens = 0;
            int idx = start;
            while (idx < listRun.size()) {
                DocumentElement item = listRun.get(idx);
                int itemTokens = estimateTokens(item.getText());
                if (!slice.isEmpty() && tokens + itemTokens > config.maxTokensPerChunk()) {
                    break;
                }
                slice.add(item);
                tokens += itemTokens;
                idx++;
            }
            if (slice.isEmpty()) {
                slice.add(listRun.get(start));
                idx = start + 1;
            }
            boolean partial = slice.size() < listRun.size() - start || idx < listRun.size();
            out.add(buildListChunk(slice, section, documentId, ordinal.getAndIncrement(), config, partial));
            start = idx;
        }
    }

    private SemanticChunk buildBatchChunk(
            List<DocumentElement> batch,
            String headingPrefix,
            SectionNode section,
            String documentId,
            int ordinal,
            ChunkerConfig config,
            boolean isPartial) {

        StringBuilder content = new StringBuilder(headingPrefix);
        List<String> sourceIds = new ArrayList<>();
        int pageStart = section.pageStart();
        int pageEnd = section.pageEnd();

        for (DocumentElement el : batch) {
            appendElementText(content, el);
            if (!el.getId().isBlank()) {
                sourceIds.add(el.getId());
            }
            int page = pageOf(el);
            if (page > 0) {
                if (pageStart < 0) {
                    pageStart = page;
                }
                pageEnd = Math.max(pageEnd, page);
            }
        }

        String text = prefixSectionPath(section, config, content.toString().strip());
        ChunkType type = resolveChunkType(section, batch);
        String chunkId = documentId + "-c" + ordinal;

        return new SemanticChunk(
            chunkId, documentId, ordinal, type, text,
            null,
            section.sectionPath(),
            section.heading(),
            List.copyOf(sourceIds),
            pageStart, pageEnd,
            estimateTokens(text),
            isPartial,
            Map.of(),
            sha256(text)
        );
    }

    private SemanticChunk buildTableChunk(
            DocumentElement el,
            SectionNode section,
            String documentId,
            int ordinal,
            ChunkerConfig config) {

        String rawText = el.getText();
        String caption = el.getAttributesOrDefault("caption", null);

        StringBuilder body = new StringBuilder();
        if (caption != null) {
            body.append("Table: ").append(caption).append("\n\n");
        }
        if (!rawText.isBlank()) {
            body.append(rawText);
        }

        String content = prefixSectionPath(section, config, body.toString().strip());
        TableContent tableContent = new TableContent(caption, List.of(), List.of());
        String chunkId = documentId + "-c" + ordinal;
        List<String> sourceIds = el.getId().isBlank() ? List.of() : List.of(el.getId());
        int page = pageOf(el);

        return new SemanticChunk(
            chunkId, documentId, ordinal, ChunkType.TABLE, content,
            new StructuredContent(tableContent, null, null),
            section.sectionPath(),
            section.heading(),
            sourceIds,
            page, page,
            estimateTokens(content),
            false,
            Map.of(),
            sha256(content)
        );
    }

    private SemanticChunk buildListChunk(
            List<DocumentElement> items,
            SectionNode section,
            String documentId,
            int ordinal,
            ChunkerConfig config,
            boolean isPartial) {

        StringBuilder body = new StringBuilder();
        List<String> sourceIds = new ArrayList<>();
        int pageStart = -1;
        int pageEnd = -1;

        for (DocumentElement item : items) {
            if (!body.isEmpty()) {
                body.append('\n');
            }
            String text = item.getText().isBlank() ? "•" : item.getText();
            body.append(text.startsWith("•") || text.startsWith("-") ? text : "• " + text);
            if (!item.getId().isBlank()) {
                sourceIds.add(item.getId());
            }
            int page = pageOf(item);
            if (page > 0) {
                if (pageStart < 0) {
                    pageStart = page;
                }
                pageEnd = Math.max(pageEnd, page);
            }
        }

        List<String> listItems = items.stream()
            .map(DocumentElement::getText)
            .filter(t -> t != null && !t.isBlank())
            .toList();
        String content = prefixSectionPath(section, config, body.toString().strip());
        String chunkId = documentId + "-c" + ordinal;

        return new SemanticChunk(
            chunkId, documentId, ordinal, ChunkType.LIST, content,
            new StructuredContent(null, new ListContent(listItems), null),
            section.sectionPath(),
            section.heading(),
            List.copyOf(sourceIds),
            pageStart, pageEnd,
            estimateTokens(content),
            isPartial,
            Map.of(),
            sha256(content)
        );
    }

    private SemanticChunk buildFigureChunk(
            DocumentElement image,
            DocumentElement captionEl,
            SectionNode section,
            String documentId,
            int ordinal,
            ChunkerConfig config) {

        String caption = captionEl != null
            ? captionEl.getText()
            : image.getAttributesOrDefault("caption", null);
        String description = image.getText();
        if (description.isBlank()) {
            description = image.getAlternateRepresentation();
        }

        StringBuilder body = new StringBuilder();
        if (caption != null && !caption.isBlank()) {
            body.append("Figure: ").append(caption);
        } else if (!description.isBlank()) {
            body.append("Figure");
        }
        if (!description.isBlank()) {
            if (!body.isEmpty()) {
                body.append("\n\n");
            }
            body.append(description);
        }

        String content = prefixSectionPath(section, config, body.toString().strip());
        List<String> sourceIds = new ArrayList<>();
        if (!image.getId().isBlank()) {
            sourceIds.add(image.getId());
        }
        if (captionEl != null && !captionEl.getId().isBlank()) {
            sourceIds.add(captionEl.getId());
        }

        int page = pageOf(image);
        if (page < 0 && captionEl != null) {
            page = pageOf(captionEl);
        }

        return new SemanticChunk(
            documentId + "-c" + ordinal, documentId, ordinal, ChunkType.FIGURE, content,
            new StructuredContent(null, null, new FigureContent(caption, description)),
            section.sectionPath(),
            section.heading(),
            List.copyOf(sourceIds),
            page, page,
            estimateTokens(content),
            false,
            Map.of(),
            sha256(content)
        );
    }

    private static boolean isListElement(DocumentElement el) {
        return el.getType() == DocumentElementType.ELEMENT_LIST
            || el.getType() == DocumentElementType.ELEMENT_LIST_ITEM;
    }

    private static List<DocumentElement> collectListRun(List<DocumentElement> elements, int start) {
        List<DocumentElement> run = new ArrayList<>();
        DocumentElement first = elements.get(start);
        if (first.getType() == DocumentElementType.ELEMENT_LIST) {
            run.add(first);
            for (int i = start + 1; i < elements.size(); i++) {
                DocumentElement el = elements.get(i);
                if (el.getType() == DocumentElementType.ELEMENT_LIST_ITEM) {
                    run.add(el);
                } else {
                    break;
                }
            }
            return run;
        }
        for (int i = start; i < elements.size(); i++) {
            DocumentElement el = elements.get(i);
            if (el.getType() == DocumentElementType.ELEMENT_LIST_ITEM) {
                run.add(el);
            } else {
                break;
            }
        }
        return run.isEmpty() ? List.of(first) : run;
    }

    private static DocumentElement peekCaption(List<DocumentElement> elements, int index) {
        if (index >= elements.size()) {
            return null;
        }
        DocumentElement next = elements.get(index);
        return next.getType() == DocumentElementType.ELEMENT_CAPTION ? next : null;
    }

    private static void appendElementText(StringBuilder content, DocumentElement el) {
        if (el.getText().isBlank()) {
            return;
        }
        if (!content.isEmpty() && content.charAt(content.length() - 1) != '\n') {
            content.append("\n\n");
        }
        content.append(el.getText());
    }

    private static String prefixSectionPath(SectionNode section, ChunkerConfig config, String content) {
        if (!config.includeSectionPath() || section.sectionPath().isEmpty()) {
            return content;
        }
        String breadcrumb = String.join(" > ", section.sectionPath());
        if (content.startsWith(breadcrumb)) {
            return content;
        }
        return breadcrumb + "\n\n" + content;
    }

    private static ChunkType resolveChunkType(SectionNode section, List<DocumentElement> batch) {
        if (section.headingLevel() <= 1) {
            return ChunkType.SECTION;
        }
        return ChunkType.SUBSECTION;
    }

    private static int pageOf(DocumentElement el) {
        return el.hasLocation() ? el.getLocation().getPage() : -1;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
