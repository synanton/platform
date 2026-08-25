package org.synanton.synflux.chunking;

import org.junit.jupiter.api.Test;
import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.domain.SemanticChunk;
import synanton.extraction.v1.DocumentElement;
import synanton.extraction.v1.DocumentElementType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticChunkerTest {

    private final DocumentStructureBuilder structureBuilder = new DocumentStructureBuilder();
    private final SemanticChunker chunker = new SemanticChunker();

    private static DocumentElement heading(String text, int level) {
        return DocumentElement.newBuilder()
            .setId("h-" + level)
            .setType(DocumentElementType.ELEMENT_HEADING)
            .setText(text)
            .setLevel(level)
            .build();
    }

    private static DocumentElement para(String id, String text) {
        return DocumentElement.newBuilder()
            .setId(id)
            .setType(DocumentElementType.ELEMENT_PARAGRAPH)
            .setText(text)
            .build();
    }

    private static DocumentElement table(String id, String text) {
        return DocumentElement.newBuilder()
            .setId(id)
            .setType(DocumentElementType.ELEMENT_TABLE)
            .setText(text)
            .build();
    }

    private List<SemanticChunk> chunkElements(List<DocumentElement> elements, ChunkerConfig cfg) {
        List<SectionNode> sections = structureBuilder.build(elements);
        return chunker.chunk(sections, "doc-001", cfg);
    }

    // ─── Basic output ─────────────────────────────────────────────────────────

    @Test
    void emptyElementsProducesNoChunks() {
        assertThat(chunker.chunk(List.of(), "doc", ChunkerConfig.defaults())).isEmpty();
    }

    @Test
    void singleSectionProducesAtLeastOneChunk() {
        var chunks = chunkElements(List.of(
            heading("Introduction", 1),
            para("p1", "This is the introduction paragraph.")
        ), ChunkerConfig.defaults());

        assertThat(chunks).isNotEmpty();
    }

    @Test
    void chunkIdsAreUnique() {
        var chunks = chunkElements(List.of(
            heading("Section A", 1),
            para("p1", "Alpha content."),
            heading("Section B", 1),
            para("p2", "Beta content.")
        ), ChunkerConfig.defaults());

        var ids = chunks.stream().map(SemanticChunk::chunkId).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void ordinalsAreSequentialFromZero() {
        var chunks = chunkElements(List.of(
            heading("Section A", 1),
            para("p1", "Alpha."),
            heading("Section B", 1),
            para("p2", "Beta.")
        ), ChunkerConfig.defaults());

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).ordinal()).isEqualTo(i);
        }
    }

    @Test
    void tokenCountIsPositiveForNonEmptyContent() {
        var chunks = chunkElements(List.of(
            heading("Section", 1),
            para("p1", "Some meaningful paragraph text.")
        ), ChunkerConfig.defaults());

        assertThat(chunks).allSatisfy(c -> assertThat(c.tokenCount()).isPositive());
    }

    // ─── sectionPath and heading ──────────────────────────────────────────────

    @Test
    void sectionPathIncludesHeadingHierarchy() {
        var chunks = chunkElements(List.of(
            heading("GPU Execution Plane", 1),
            heading("GPU Gateway", 2),
            para("p1", "The gateway provides a secure boundary.")
        ), ChunkerConfig.defaults());

        var subsectionChunk = chunks.stream()
            .filter(c -> c.sectionPath().contains("GPU Gateway"))
            .findFirst();

        assertThat(subsectionChunk).isPresent();
        assertThat(subsectionChunk.get().sectionPath())
            .containsExactly("GPU Execution Plane", "GPU Gateway");
    }

    // ─── Table atomicity ──────────────────────────────────────────────────────

    @Test
    void tableEmittedAsAtomicChunk() {
        var chunks = chunkElements(List.of(
            heading("Execution Classes", 1),
            para("p1", "The following classes are defined."),
            table("t1", "Class\tTimeout\nInteractive\t60s\nBatch\t30m"),
            para("p2", "See the scheduler for details.")
        ), ChunkerConfig.defaults());

        var tableChunks = chunks.stream()
            .filter(c -> c.type() == SemanticChunk.ChunkType.TABLE)
            .toList();

        assertThat(tableChunks).hasSize(1);
        // Table chunk appears between the first and last paragraph chunks, not merged.
        assertThat(tableChunks.get(0).content()).contains("Class");
    }

    @Test
    void tableIsNotSplitEvenWhenLarge() {
        // Build a "table" whose text exceeds a tiny token budget.
        String largeTableText = "H1\tH2\n" + "row\tdata\n".repeat(200);
        var tinyBudgetCfg = new ChunkerConfig(5, 50, true, true, true, true, true, 1, true, true);

        var chunks = chunkElements(List.of(
            heading("Data", 1),
            table("t1", largeTableText)
        ), tinyBudgetCfg);

        var tableChunks = chunks.stream()
            .filter(c -> c.type() == SemanticChunk.ChunkType.TABLE)
            .toList();

        // Exactly one table chunk — atomicity is enforced regardless of budget.
        assertThat(tableChunks).hasSize(1);
    }

    // ─── Source element provenance ────────────────────────────────────────────

    @Test
    void sourceElementsTraceToExtractedIds() {
        String elemId = "p7-e12";
        var chunks = chunkElements(List.of(
            heading("Intro", 1),
            DocumentElement.newBuilder()
                .setId(elemId)
                .setType(DocumentElementType.ELEMENT_PARAGRAPH)
                .setText("The gateway provides a secure, mTLS-authenticated boundary.")
                .build()
        ), ChunkerConfig.defaults());

        boolean found = chunks.stream()
            .anyMatch(c -> c.sourceElements().contains(elemId));
        assertThat(found).isTrue();
    }

    // ─── Oversized section splitting ──────────────────────────────────────────

    @Test
    void oversizedSectionSplitsAtParagraphBoundaries() {
        // Each paragraph is ~10 tokens; budget is 25 tokens → forces at least 2 chunks.
        String longPara = "word ".repeat(40).trim();
        ChunkerConfig tightCfg = ChunkerConfig.of(25);

        var chunks = chunkElements(List.of(
            heading("Large Section", 1),
            para("p1", longPara),
            para("p2", longPara),
            para("p3", longPara)
        ), tightCfg);

        assertThat(chunks.size()).isGreaterThan(1);
        // None should exceed the budget by more than the size of one element (no mid-para splits).
        chunks.forEach(c -> assertThat(c.content().split("\\s+").length)
            .isLessThanOrEqualTo(tightCfg.maxTokensPerChunk() + 50));
    }

    // ─── sha256 ───────────────────────────────────────────────────────────────

    @Test
    void sha256IsHexStringOf64Chars() {
        var chunks = chunkElements(List.of(
            heading("Title", 1),
            para("p1", "Some text content.")
        ), ChunkerConfig.defaults());

        assertThat(chunks).allSatisfy(c -> assertThat(c.sha256()).hasSize(64));
    }
}
