package org.synanton.synflux.chunking;

import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.domain.SemanticChunk.ChunkType;
import org.synanton.synflux.domain.SemanticChunk.FigureContent;
import org.synanton.synflux.domain.SemanticChunk.ListContent;
import org.synanton.synflux.domain.SemanticChunk.StructuredContent;
import org.synanton.synflux.domain.SemanticChunk.TableContent;
import org.synanton.extraction.v1.DocumentElement;
import org.synanton.extraction.v1.DocumentElementType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts a section tree into a flat list of {@link SemanticChunk}s.
 */
public class SemanticChunker {

    public List<SemanticChunk> chunk(
            List<SectionNode> sections,
            String documentId,
            ChunkerConfig config) {
        return chunk(sections, documentId, config, ChunkTextPolicy.RETRIEVAL, CanonicalTextEvidence.absent());
    }

    public List<SemanticChunk> chunk(
            List<SectionNode> sections,
            String documentId,
            ChunkerConfig config,
            ChunkTextPolicy policy,
            CanonicalTextEvidence canonical) {

        List<SemanticChunk> result = new ArrayList<>();
        AtomicInteger ordinal = new AtomicInteger();
        for (SectionNode section : sections) {
            chunkSection(section, "", documentId, config, policy, canonical, result, ordinal);
        }
        return Collections.unmodifiableList(result);
    }

    private void chunkSection(
            SectionNode section,
            String parentSectionId,
            String documentId,
            ChunkerConfig config,
            ChunkTextPolicy policy,
            CanonicalTextEvidence canonical,
            List<SemanticChunk> out,
            AtomicInteger ordinal) {

        int firstOwn = out.size();
        List<DocumentElement> elements = section.elements();
        Map<String, DocumentElement> byId = new HashMap<>();
        for (DocumentElement element : elements) {
            byId.put(element.getId(), element);
        }
        Set<String> tableDescendants = config.keepTableAtomic()
            ? tableDescendantIds(elements, byId) : Set.of();
        List<DocumentElement> batch = new ArrayList<>();
        int batchTokens = 0;

        if (policy == ChunkTextPolicy.BENCHMARK_V1
                && section.headingElement() != null
                && elements.isEmpty()
                && !section.children().isEmpty()) {
            out.add(buildContainerChunk(section, documentId, ordinal.getAndIncrement(), canonical));
        }

        String headingPrefix = ((policy == ChunkTextPolicy.BENCHMARK_V1 || config.includeHeadingInContent())
                && section.heading() != null)
            ? section.heading() + "\n\n"
            : "";
        if (!headingPrefix.isEmpty()) {
            batchTokens += estimateTokens(headingPrefix);
        }

        for (int i = 0; i < elements.size(); i++) {
            DocumentElement el = elements.get(i);
            if (tableDescendants.contains(el.getId())) {
                continue;
            }

            if (el.getType() == DocumentElementType.ELEMENT_TABLE && config.keepTableAtomic()) {
                flushBatch(batch, headingPrefix, section, documentId, config, policy, canonical, out, ordinal);
                batch.clear();
                batchTokens = 0;
                headingPrefix = "";
                out.add(buildTableChunk(el, section, documentId, ordinal.getAndIncrement(), config, policy, canonical));
                continue;
            }

            if (config.keepListAtomic() && isListElement(el)) {
                flushBatch(batch, headingPrefix, section, documentId, config, policy, canonical, out, ordinal);
                batch.clear();
                batchTokens = 0;
                headingPrefix = "";
                List<DocumentElement> listRun = collectListRun(elements, i);
                emitListChunks(listRun, section, documentId, config, policy, canonical, out, ordinal);
                i += listRun.size() - 1;
                continue;
            }

            if (config.keepFigureWithCaption() && el.getType() == DocumentElementType.ELEMENT_IMAGE) {
                flushBatch(batch, headingPrefix, section, documentId, config, policy, canonical, out, ordinal);
                batch.clear();
                batchTokens = 0;
                headingPrefix = "";
                DocumentElement caption = peekCaption(elements, i + 1);
                out.add(buildFigureChunk(el, caption, section, documentId, ordinal.getAndIncrement(),
                    config, policy, canonical));
                if (caption != null) {
                    i++;
                }
                continue;
            }

            int elTokens = estimateTokens(el.getText());
            if (!batch.isEmpty() && batchTokens + elTokens > config.maxTokensPerChunk()) {
                flushBatch(batch, headingPrefix, section, documentId, config, policy, canonical, out, ordinal);
                batch.clear();
                batchTokens = 0;
                headingPrefix = "";
            }
            batch.add(el);
            batchTokens += elTokens;
        }

        flushBatch(batch, headingPrefix, section, documentId, config, policy, canonical, out, ordinal);

        // B2/T05: tag this section's own chunks (children tag theirs) with the hierarchy, and
        // mark paragraph batches partial when the section's text spans more than one of them.
        long batches = out.subList(firstOwn, out.size()).stream()
            .filter(c -> c.type() == ChunkType.SECTION || c.type() == ChunkType.SUBSECTION).count();
        for (int k = firstOwn; k < out.size(); k++) {
            SemanticChunk c = out.get(k);
            boolean isBatch = c.type() == ChunkType.SECTION || c.type() == ChunkType.SUBSECTION;
            out.set(k, c.withHierarchy(section.id(), parentSectionId, section.headingLevel(),
                c.isPartialSection() || (isBatch && batches > 1)));
        }
        for (SectionNode child : section.children()) {
            chunkSection(child, section.id(), documentId, config, policy, canonical, out, ordinal);
        }
    }

    private void flushBatch(
            List<DocumentElement> batch,
            String headingPrefix,
            SectionNode section,
            String documentId,
            ChunkerConfig config,
            ChunkTextPolicy policy,
            CanonicalTextEvidence canonical,
            List<SemanticChunk> out,
            AtomicInteger ordinal) {
        if (batch.isEmpty()) {
            return;
        }
        out.add(buildBatchChunk(batch, headingPrefix, section, documentId,
            ordinal.getAndIncrement(), config, policy, canonical, false));
    }

    private void emitListChunks(
            List<DocumentElement> listRun,
            SectionNode section,
            String documentId,
            ChunkerConfig config,
            ChunkTextPolicy policy,
            CanonicalTextEvidence canonical,
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
            out.add(buildListChunk(slice, section, documentId, ordinal.getAndIncrement(),
                config, policy, canonical, partial));
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
            ChunkTextPolicy policy,
            CanonicalTextEvidence canonical,
            boolean isPartial) {

        StringBuilder content = new StringBuilder(headingPrefix);
        List<String> sourceIds = new ArrayList<>();
        int pageStart = section.pageStart();
        int pageEnd = section.pageEnd();

        if (policy == ChunkTextPolicy.BENCHMARK_V1 && section.headingElement() != null
                && !section.headingElement().getId().isBlank()) {
            sourceIds.add(section.headingElement().getId());
        }
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

        String text = policy == ChunkTextPolicy.BENCHMARK_V1
            ? content.toString().strip()
            : prefixSectionPath(section, config, content.toString().strip());
        ChunkType type = resolveChunkType(section, batch);
        String chunkId = documentId + "-c" + ordinal;
        EvidenceRange evidence = contiguousEvidence(section, batch, canonical);

        SemanticChunk chunk = new SemanticChunk(
            chunkId, documentId, ordinal, type, text,
            null,
            section.sectionPath(),
            section.heading(),
            List.copyOf(sourceIds),
            pageStart, pageEnd,
            estimateTokens(text),
            isPartial,
            Map.of(),
            SemanticChunk.PUBLIC_ONLY,
            sha256(text)
        );
        return applyEvidence(chunk, evidence, policy);
    }

    private SemanticChunk buildTableChunk(
            DocumentElement el,
            SectionNode section,
            String documentId,
            int ordinal,
            ChunkerConfig config,
            ChunkTextPolicy policy,
            CanonicalTextEvidence canonical) {

        String rawText = el.getText();
        String caption = el.getAttributesOrDefault("caption", null);

        StringBuilder body = new StringBuilder();
        if (policy == ChunkTextPolicy.BENCHMARK_V1 && section.heading() != null) {
            body.append(section.heading()).append("\n\n");
        }
        if (caption != null) {
            body.append("Table: ").append(caption).append("\n\n");
        }
        if (!rawText.isBlank()) {
            body.append(rawText);
        }

        String content = policy == ChunkTextPolicy.BENCHMARK_V1
            ? body.toString().strip()
            : prefixSectionPath(section, config, body.toString().strip());
        TableContent tableContent = new TableContent(caption, List.of(), List.of());
        String chunkId = documentId + "-c" + ordinal;
        List<String> sourceIds = new ArrayList<>();
        if (policy == ChunkTextPolicy.BENCHMARK_V1 && section.headingElement() != null
                && !section.headingElement().getId().isBlank()) {
            sourceIds.add(section.headingElement().getId());
        }
        if (!el.getId().isBlank()) {
            sourceIds.add(el.getId());
        }
        Map<String, DocumentElement> byId = new HashMap<>();
        for (DocumentElement element : section.elements()) {
            byId.put(element.getId(), element);
        }
        Set<String> descendants = new HashSet<>();
        collectDescendants(el, byId, descendants);
        descendants.stream().sorted().forEach(sourceIds::add);
        int page = pageOf(el);

        SemanticChunk chunk = new SemanticChunk(
            chunkId, documentId, ordinal, ChunkType.TABLE, content,
            new StructuredContent(tableContent, null, null),
            section.sectionPath(),
            section.heading(),
            List.copyOf(sourceIds),
            page, page,
            estimateTokens(content),
            false,
            Map.of(),
            SemanticChunk.PUBLIC_ONLY,
            sha256(content)
        );
        Optional<CanonicalTextEvidence.Range> tableRange = canonical.range(el.getId());
        return applyEvidence(chunk, tableRange
                .map(range -> new EvidenceRange(Optional.of(range), ""))
                .orElseGet(() -> new EvidenceRange(Optional.empty(), evidenceReason(canonical))), policy);
    }

    private SemanticChunk buildListChunk(
            List<DocumentElement> items,
            SectionNode section,
            String documentId,
            int ordinal,
            ChunkerConfig config,
            ChunkTextPolicy policy,
            CanonicalTextEvidence canonical,
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
        String textBody = body.toString().strip();
        if (policy == ChunkTextPolicy.BENCHMARK_V1 && section.heading() != null) {
            textBody = section.heading() + "\n\n" + textBody;
            if (section.headingElement() != null && !section.headingElement().getId().isBlank()) {
                sourceIds.add(0, section.headingElement().getId());
            }
        }
        String content = policy == ChunkTextPolicy.BENCHMARK_V1
            ? textBody
            : prefixSectionPath(section, config, textBody);
        String chunkId = documentId + "-c" + ordinal;
        EvidenceRange evidence = contiguousEvidence(section, items, canonical);

        SemanticChunk chunk = new SemanticChunk(
            chunkId, documentId, ordinal, ChunkType.LIST, content,
            new StructuredContent(null, new ListContent(listItems), null),
            section.sectionPath(),
            section.heading(),
            List.copyOf(sourceIds),
            pageStart, pageEnd,
            estimateTokens(content),
            isPartial,
            Map.of(),
            SemanticChunk.PUBLIC_ONLY,
            sha256(content)
        );
        return applyEvidence(chunk, evidence, policy);
    }

    private SemanticChunk buildFigureChunk(
            DocumentElement image,
            DocumentElement captionEl,
            SectionNode section,
            String documentId,
            int ordinal,
            ChunkerConfig config,
            ChunkTextPolicy policy,
            CanonicalTextEvidence canonical) {

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

        String textBody = body.toString().strip();
        if (policy == ChunkTextPolicy.BENCHMARK_V1 && section.heading() != null) {
            textBody = section.heading() + "\n\n" + textBody;
        }
        String content = policy == ChunkTextPolicy.BENCHMARK_V1
            ? textBody
            : prefixSectionPath(section, config, textBody);
        List<String> sourceIds = new ArrayList<>();
        if (policy == ChunkTextPolicy.BENCHMARK_V1 && section.headingElement() != null
                && !section.headingElement().getId().isBlank()) {
            sourceIds.add(section.headingElement().getId());
        }
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

        List<DocumentElement> evidenceElements = new ArrayList<>();
        evidenceElements.add(image);
        if (captionEl != null) {
            evidenceElements.add(captionEl);
        }
        SemanticChunk chunk = new SemanticChunk(
            documentId + "-c" + ordinal, documentId, ordinal, ChunkType.FIGURE, content,
            new StructuredContent(null, null, new FigureContent(caption, description)),
            section.sectionPath(),
            section.heading(),
            List.copyOf(sourceIds),
            page, page,
            estimateTokens(content),
            false,
            Map.of(),
            SemanticChunk.PUBLIC_ONLY,
            sha256(content)
        );
        return applyEvidence(chunk, contiguousEvidence(section, evidenceElements, canonical), policy);
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

    private SemanticChunk buildContainerChunk(
            SectionNode section,
            String documentId,
            int ordinal,
            CanonicalTextEvidence canonical) {

        DocumentElement heading = section.headingElement();
        List<String> sourceIds = heading == null || heading.getId().isBlank()
            ? List.of() : List.of(heading.getId());
        Optional<CanonicalTextEvidence.Range> range = containerRange(section, canonical);
        String text = range.flatMap(canonical::text).orElseGet(() -> section.heading() == null ? "" : section.heading());
        EvidenceRange evidence = range
            .map(value -> new EvidenceRange(Optional.of(value), ""))
            .orElseGet(() -> new EvidenceRange(Optional.empty(), evidenceReason(canonical)));
        SemanticChunk chunk = new SemanticChunk(
            documentId + "-c" + ordinal, documentId, ordinal, ChunkType.HEADING, text,
            null, section.sectionPath(), section.heading(), sourceIds,
            section.pageStart(), section.pageEnd(), estimateTokens(text), false,
            Map.of(), SemanticChunk.PUBLIC_ONLY, sha256(text)
        );
        return applyEvidence(chunk, evidence, ChunkTextPolicy.BENCHMARK_V1);
    }

    private static Optional<CanonicalTextEvidence.Range> containerRange(
            SectionNode section, CanonicalTextEvidence canonical) {
        if (!canonical.available() || section.headingElement() == null || section.children().isEmpty()) {
            return Optional.empty();
        }
        Optional<CanonicalTextEvidence.Range> start = canonical.range(section.headingElement().getId());
        DocumentElement firstChildHeading = section.children().getFirst().headingElement();
        Optional<CanonicalTextEvidence.Range> end = firstChildHeading == null
            ? Optional.empty() : canonical.range(firstChildHeading.getId());
        if (start.isEmpty() || end.isEmpty() || end.get().start() <= start.get().start()) {
            return Optional.empty();
        }
        return Optional.of(new CanonicalTextEvidence.Range(start.get().start(), end.get().start()));
    }

    private static EvidenceRange contiguousEvidence(
            SectionNode section,
            List<DocumentElement> elements,
            CanonicalTextEvidence canonical) {
        if (!canonical.available()) {
            return new EvidenceRange(Optional.empty(), evidenceReason(canonical));
        }
        List<CanonicalTextEvidence.Range> ranges = new ArrayList<>();
        if (section.headingElement() != null) {
            Optional<CanonicalTextEvidence.Range> headingRange =
                canonical.range(section.headingElement().getId());
            if (headingRange.isEmpty()) {
                return new EvidenceRange(Optional.empty(), "CANONICAL_ELEMENT_RANGE_MISSING");
            }
            ranges.add(headingRange.get());
        }
        for (DocumentElement element : elements) {
            Optional<CanonicalTextEvidence.Range> range = canonical.range(element.getId());
            if (range.isEmpty()) {
                return new EvidenceRange(Optional.empty(), "CANONICAL_ELEMENT_RANGE_MISSING");
            }
            ranges.add(range.get());
        }
        ranges.sort(java.util.Comparator.comparingInt(CanonicalTextEvidence.Range::start));
        int start = ranges.getFirst().start();
        int end = ranges.getFirst().end();
        for (CanonicalTextEvidence.Range next : ranges.subList(1, ranges.size())) {
            if (next.start() <= end) {
                end = Math.max(end, next.end());
                continue;
            }
            String gap = canonical.text(new CanonicalTextEvidence.Range(end, next.start())).orElse("");
            if (!isUnicodeWhitespace(gap)) {
                return new EvidenceRange(Optional.empty(), "DISCONTIGUOUS_SOURCE_EVIDENCE");
            }
            end = next.end();
        }
        return new EvidenceRange(Optional.of(new CanonicalTextEvidence.Range(start, end)), "");
    }

    private static boolean isUnicodeWhitespace(String value) {
        return value.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp));
    }

    private static String evidenceReason(CanonicalTextEvidence canonical) {
        return canonical.reason().isBlank() ? "CANONICAL_EVIDENCE_UNAVAILABLE" : canonical.reason();
    }

    private static SemanticChunk applyEvidence(SemanticChunk chunk, EvidenceRange evidence,
                                               ChunkTextPolicy policy) {
        Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata());
        if (policy == ChunkTextPolicy.BENCHMARK_V1 && evidence.range().isEmpty()) {
            metadata.put("evidence_status", evidence.reason());
        }
        return evidence.range()
            .map(range -> chunk.withEvidence(range.start(), range.end(), metadata))
            .orElseGet(() -> chunk.withEvidence(-1, -1, metadata));
    }

    private static Set<String> tableDescendantIds(
            List<DocumentElement> elements, Map<String, DocumentElement> byId) {
        Set<String> descendants = new HashSet<>();
        for (DocumentElement element : elements) {
            if (element.getType() == DocumentElementType.ELEMENT_TABLE) {
                collectDescendants(element, byId, descendants);
            }
        }
        return descendants;
    }

    private static void collectDescendants(DocumentElement element,
                                           Map<String, DocumentElement> byId,
                                           Set<String> descendants) {
        for (String childId : element.getChildIdsList()) {
            if (!descendants.add(childId)) {
                continue;
            }
            DocumentElement child = byId.get(childId);
            if (child != null) {
                collectDescendants(child, byId, descendants);
            }
        }
    }

    private record EvidenceRange(Optional<CanonicalTextEvidence.Range> range, String reason) {}

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
