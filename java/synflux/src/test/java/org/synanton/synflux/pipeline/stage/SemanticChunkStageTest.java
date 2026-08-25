package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.Test;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;
import synanton.extraction.v1.DocumentElement;
import synanton.extraction.v1.DocumentElementType;
import synanton.extraction.v1.DocumentPayload;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticChunkStageTest {

    private static final UUID CONTENT_REF = UUID.randomUUID();

    private static ParsedDocument parsedDocWithPayload(DocumentPayload payload) {
        var ref = new ContentRef("file", "file:///test.pdf", "application/pdf", 1000, Instant.now());
        var acquired = new AcquiredDocument(ref, new byte[0], "sha256", "application/pdf",
            "file:///test.pdf", CONTENT_REF);
        return new ParsedDocument(acquired, "flat text", Map.of(), payload);
    }

    private static ParsedDocument parsedDocNoPayload(String flatText) {
        var ref = new ContentRef("file", "file:///test.txt", "text/plain", flatText.length(), Instant.now());
        var acquired = new AcquiredDocument(ref, flatText.getBytes(), "sha256", "text/plain",
            "file:///test.txt", CONTENT_REF);
        return new ParsedDocument(acquired, flatText, Map.of(), null);
    }

    private static StageContext ctx() {
        return new StageContext("demo", UUID.randomUUID().toString(), null);
    }

    private static DocumentElement heading(String text, int level) {
        return DocumentElement.newBuilder()
            .setId("h-" + level + "-" + text.hashCode())
            .setType(DocumentElementType.ELEMENT_HEADING)
            .setText(text)
            .setLevel(level)
            .build();
    }

    private static DocumentElement paragraph(String text) {
        return DocumentElement.newBuilder()
            .setId("p-" + text.hashCode())
            .setType(DocumentElementType.ELEMENT_PARAGRAPH)
            .setText(text)
            .build();
    }

    private static DocumentElement table(String text) {
        return DocumentElement.newBuilder()
            .setId("t-" + text.hashCode())
            .setType(DocumentElementType.ELEMENT_TABLE)
            .setText(text)
            .build();
    }

    // ─── Structured payload ───────────────────────────────────────────────────

    @Test
    void structuredPayload_sectionPathPreservedInEveryChunk() {
        DocumentPayload payload = DocumentPayload.newBuilder()
            .addElements(heading("Chapter 3. GPU Execution Plane", 1))
            .addElements(paragraph("The GPU execution plane is separate from the CPU control plane."))
            .addElements(heading("3.1 GPU Gateway", 2))
            .addElements(paragraph("The gateway provides a secure boundary."))
            .build();

        var stage = new SemanticChunkStage(ChunkerConfig.defaults());
        var result = stage.apply(parsedDocWithPayload(payload), ctx());

        assertThat(result.chunks()).isNotEmpty();
        // Every chunk must carry a sectionPath
        assertThat(result.chunks()).allSatisfy(chunk ->
            assertThat(chunk.sectionPath()).isNotNull()
        );
        // The subsection chunk must include parent heading in path
        var subsectionChunk = result.chunks().stream()
            .filter(c -> c.sectionPath().contains("3.1 GPU Gateway"))
            .findFirst();
        assertThat(subsectionChunk).isPresent();
        assertThat(subsectionChunk.get().sectionPath())
            .containsExactly("Chapter 3. GPU Execution Plane", "3.1 GPU Gateway");
    }

    @Test
    void structuredPayload_tableEmittedAsAtomicChunk() {
        DocumentPayload payload = DocumentPayload.newBuilder()
            .addElements(heading("Scheduling", 1))
            .addElements(paragraph("The scheduler uses priority queues."))
            .addElements(table("Class\tTimeout\nInteractive\t60s\nBatch\t30m"))
            .addElements(paragraph("More scheduling details follow."))
            .build();

        var stage = new SemanticChunkStage(ChunkerConfig.defaults());
        var result = stage.apply(parsedDocWithPayload(payload), ctx());

        List<SemanticChunk> tableChunks = result.chunks().stream()
            .filter(c -> c.type() == SemanticChunk.ChunkType.TABLE)
            .toList();
        assertThat(tableChunks).hasSize(1);
        assertThat(tableChunks.get(0).content()).contains("Class");
    }

    @Test
    void structuredPayload_sourceElementsTraceToExtraction() {
        String elemId = "p1-e07";
        DocumentPayload payload = DocumentPayload.newBuilder()
            .addElements(heading("Introduction", 1))
            .addElements(DocumentElement.newBuilder()
                .setId(elemId)
                .setType(DocumentElementType.ELEMENT_PARAGRAPH)
                .setText("Enterprise content extraction.")
                .build())
            .build();

        var stage = new SemanticChunkStage(ChunkerConfig.defaults());
        var result = stage.apply(parsedDocWithPayload(payload), ctx());

        boolean found = result.chunks().stream()
            .anyMatch(c -> c.sourceElements().contains(elemId));
        assertThat(found).isTrue();
    }

    @Test
    void structuredPayload_chunkIdIsUnique() {
        DocumentPayload payload = DocumentPayload.newBuilder()
            .addElements(heading("Section A", 1))
            .addElements(paragraph("Alpha paragraph."))
            .addElements(heading("Section B", 1))
            .addElements(paragraph("Beta paragraph."))
            .build();

        var stage = new SemanticChunkStage(ChunkerConfig.defaults());
        var result = stage.apply(parsedDocWithPayload(payload), ctx());

        var ids = result.chunks().stream().map(SemanticChunk::chunkId).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void structuredPayload_sha256IsPopulated() {
        DocumentPayload payload = DocumentPayload.newBuilder()
            .addElements(heading("Title", 1))
            .addElements(paragraph("Some content here."))
            .build();

        var stage = new SemanticChunkStage(ChunkerConfig.defaults());
        var result = stage.apply(parsedDocWithPayload(payload), ctx());

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(c ->
            assertThat(c.sha256()).hasSize(64)
        );
    }

    // ─── Fallback (no payload) ────────────────────────────────────────────────

    @Test
    void noPayload_fallsBackToTokenSplit() {
        String text = String.join(" ", java.util.Collections.nCopies(100, "word"));
        var stage = new SemanticChunkStage(ChunkerConfig.of(20));
        var result = stage.apply(parsedDocNoPayload(text), ctx());

        assertThat(result.chunks().size()).isGreaterThan(1);
        assertThat(result.chunks()).allSatisfy(c ->
            assertThat(c.type()).isEqualTo(SemanticChunk.ChunkType.FALLBACK)
        );
    }

    @Test
    void noPayload_emptyTextProducesNoChunks() {
        var stage = new SemanticChunkStage(ChunkerConfig.defaults());
        var result = stage.apply(parsedDocNoPayload("   "), ctx());
        assertThat(result.chunks()).isEmpty();
    }

    @Test
    void noPayload_singleChunkWhenTextBelowBudget() {
        var stage = new SemanticChunkStage(ChunkerConfig.defaults());
        var result = stage.apply(parsedDocNoPayload("hello world this is a test"), ctx());
        assertThat(result.chunks()).hasSize(1);
    }
}
