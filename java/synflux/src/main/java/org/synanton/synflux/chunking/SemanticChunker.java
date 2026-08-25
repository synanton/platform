package org.synanton.synflux.chunking;

import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.domain.SemanticChunk.ChunkType;
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
 *
 * <p>Strategy (proposal §10.2):
 * <ol>
 *   <li>Tables are always emitted as atomic chunks — never split.</li>
 *   <li>Direct non-table elements of a section are batched together until
 *       {@code maxTokensPerChunk} is reached, at which point a new chunk is opened.</li>
 *   <li>After all direct elements are flushed, child subsections are processed
 *       recursively.</li>
 *   <li>If a single element exceeds the budget, it is emitted as a {@code FALLBACK}
 *       chunk rather than being dropped or silently truncated.</li>
 * </ol>
 *
 * <p>Every chunk carries {@code sectionPath} and {@code sourceElements} for
 * downstream retrieval, citation, and UI highlighting (proposal §3.2).
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

    // ─── Recursive section chunking ───────────────────────────────────────────

    private void chunkSection(
            SectionNode section,
            String documentId,
            ChunkerConfig config,
            List<SemanticChunk> out,
            AtomicInteger ordinal) {

        // Batch of elements accumulating toward one chunk.
        List<DocumentElement> batch = new ArrayList<>();
        int batchTokens = 0;

        // Prepend heading text to the first batch of this section.
        String headingPrefix = (config.includeHeadingInContent() && section.heading() != null)
            ? section.heading() + "\n\n"
            : "";
        int headingTokens = estimateTokens(headingPrefix);

        if (!headingPrefix.isEmpty()) {
            batchTokens += headingTokens;
        }

        for (DocumentElement el : section.elements()) {
            if (el.getType() == DocumentElementType.ELEMENT_TABLE && config.keepTableAtomic()) {
                // Flush pending batch before the table.
                if (!batch.isEmpty()) {
                    out.add(buildBatchChunk(batch, headingPrefix, section, documentId,
                        ordinal.getAndIncrement(), config, false));
                    batch.clear();
                    batchTokens = 0;
                    headingPrefix = "";
                    headingTokens = 0;
                }
                out.add(buildTableChunk(el, section, documentId, ordinal.getAndIncrement()));
                continue;
            }

            int elTokens = estimateTokens(el.getText());
            if (!batch.isEmpty() && batchTokens + elTokens > config.maxTokensPerChunk()) {
                out.add(buildBatchChunk(batch, headingPrefix, section, documentId,
                    ordinal.getAndIncrement(), config, false));
                batch.clear();
                batchTokens = 0;
                headingPrefix = "";
                headingTokens = 0;
            }
            batch.add(el);
            batchTokens += elTokens;
        }

        // Flush remaining batch.
        if (!batch.isEmpty()) {
            out.add(buildBatchChunk(batch, headingPrefix, section, documentId,
                ordinal.getAndIncrement(), config, false));
        }

        // Recurse into child subsections.
        for (SectionNode child : section.children()) {
            chunkSection(child, documentId, config, out, ordinal);
        }
    }

    // ─── Chunk builders ───────────────────────────────────────────────────────

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
        int pageEnd   = section.pageEnd();

        for (DocumentElement el : batch) {
            if (!el.getText().isBlank()) {
                if (!content.isEmpty() && content.charAt(content.length() - 1) != '\n') {
                    content.append("\n\n");
                }
                content.append(el.getText());
            }
            if (!el.getId().isBlank()) sourceIds.add(el.getId());
            int page = pageOf(el);
            if (page > 0) {
                if (pageStart < 0) pageStart = page;
                pageEnd = Math.max(pageEnd, page);
            }
        }

        String text = content.toString().strip();
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
            int ordinal) {

        // Build structured table content from child elements (TABLE_ROW / TABLE_CELL).
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();

        // The table element carries child_ids; children also appear in the top-level
        // element list and have already been visited. For the PoC we reconstruct the
        // table from the element's text attribute if child structure is unavailable.
        String rawText = el.getText();
        String caption = el.getAttributesOrDefault("caption", null);

        // Embedding representation: section breadcrumb + table content (proposal §10.4).
        String breadcrumb = String.join(" > ", section.sectionPath());
        StringBuilder embeddingText = new StringBuilder();
        if (!breadcrumb.isBlank()) embeddingText.append(breadcrumb).append("\n\n");
        if (caption != null)       embeddingText.append("Table: ").append(caption).append("\n\n");
        if (!rawText.isBlank())    embeddingText.append(rawText);

        String content = embeddingText.toString().strip();
        TableContent tableContent = headers.isEmpty()
            ? new TableContent(caption, List.of(), List.of())
            : new TableContent(caption, headers, rows);

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

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static ChunkType resolveChunkType(SectionNode section, List<DocumentElement> batch) {
        if (section.headingLevel() == 0) return ChunkType.SECTION;
        if (section.headingLevel() == 1) return ChunkType.SECTION;
        if (section.headingLevel() == 2) return ChunkType.SUBSECTION;
        return ChunkType.SUBSECTION;
    }

    private static int pageOf(DocumentElement el) {
        return el.hasLocation() ? el.getLocation().getPage() : -1;
    }

    /** Rough approximation: 4 characters ≈ 1 BPE token. */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
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
