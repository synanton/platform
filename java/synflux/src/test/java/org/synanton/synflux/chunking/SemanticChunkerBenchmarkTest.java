package org.synanton.synflux.chunking;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.v1.CanonicalTextIdentity;
import org.synanton.extraction.v1.CanonicalTextRange;
import org.synanton.extraction.v1.DocumentElement;
import org.synanton.extraction.v1.DocumentElementType;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.DocumentProjection;
import org.synanton.extraction.v1.LineEndingPolicy;
import org.synanton.extraction.v1.OffsetUnit;
import org.synanton.extraction.v1.ProjectionOutcome;
import org.synanton.extraction.v1.SourceDescriptor;
import org.synanton.extraction.v1.TextNormalization;
import org.synanton.synflux.domain.ChunkerConfig;
import org.synanton.synflux.domain.SemanticChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticChunkerBenchmarkTest {

    private final DocumentStructureBuilder builder = new DocumentStructureBuilder();
    private final SemanticChunker chunker = new SemanticChunker();

    @Test
    void emitsContainerChunkAndOmitsEmptyLeaf() {
        var payload = DocumentPayload.newBuilder()
            .addElements(heading("Book", 1, "h1"))
            .addElements(heading("Chapter", 2, "h2"))
            .addElements(paragraph("p1", "Body"))
            .addElements(heading("Empty leaf", 2, "h3"))
            .build();

        var chunks = chunks(payload, CanonicalTextEvidence.absent());

        assertThat(chunks).filteredOn(c -> c.type() == SemanticChunk.ChunkType.HEADING)
            .singleElement().satisfies(c -> {
                assertThat(c.heading()).isEqualTo("Book");
                assertThat(c.sourceElements()).containsExactly("h1");
            });
        assertThat(chunks).noneMatch(c -> "Empty leaf".equals(c.heading()));
        assertThat(chunks).filteredOn(c -> c.sectionPath().contains("Chapter"))
            .isNotEmpty();
    }

    @Test
    void tableConsumesChildIdsAndKeepsDescendantProvenance() {
        var payload = DocumentPayload.newBuilder()
            .addElements(heading("Table section", 1, "h1"))
            .addElements(table("t1", List.of("r1", "r2")))
            .addElements(row("r1", List.of("c11", "c12")))
            .addElements(cell("c11", "A"))
            .addElements(cell("c12", "B"))
            .addElements(row("r2", List.of("c21", "c22")))
            .addElements(cell("c21", "1"))
            .addElements(cell("c22", "2"))
            .build();

        var chunks = chunks(payload, CanonicalTextEvidence.absent());
        var tables = chunks.stream().filter(c -> c.type() == SemanticChunk.ChunkType.TABLE).toList();

        assertThat(tables).singleElement().satisfies(c -> {
            assertThat(c.heading()).isEqualTo("Table section");
            assertThat(c.sourceElements()).contains("h1", "t1", "r1", "r2", "c11", "c12", "c21", "c22");
        });
        assertThat(chunks).noneMatch(c -> c.type() == SemanticChunk.ChunkType.SUBSECTION
            && c.sourceElements().contains("c11"));
    }

    @Test
    void adjacentTablesRemainIndependent() {
        var payload = DocumentPayload.newBuilder()
            .addElements(heading("Tables", 1, "h1"))
            .addElements(table("t1", List.of("r1")))
            .addElements(row("r1", List.of("c1")))
            .addElements(cell("c1", "one"))
            .addElements(table("t2", List.of("r2")))
            .addElements(row("r2", List.of("c2")))
            .addElements(cell("c2", "two"))
            .build();

        var chunks = chunks(payload, CanonicalTextEvidence.absent());
        var tables = chunks.stream().filter(c -> c.type() == SemanticChunk.ChunkType.TABLE).toList();

        assertThat(tables).hasSize(2);
        assertThat(tables.get(0).sourceElements()).contains("t1", "r1", "c1");
        assertThat(tables.get(1).sourceElements()).contains("t2", "r2", "c2");
    }

    @Test
    void contentChunkRetainsHeadingProvenance() {
        var payload = DocumentPayload.newBuilder()
            .addElements(heading("Section", 1, "h1"))
            .addElements(paragraph("p1", "Body"))
            .build();

        var body = chunks(payload, CanonicalTextEvidence.absent()).stream()
            .filter(c -> c.type() == SemanticChunk.ChunkType.SECTION).findFirst().orElseThrow();

        assertThat(body.sourceElements()).containsExactly("h1", "p1");
    }

    @Test
    void tableUsesDeclaredTableRangeNotDescendantMinMax() {
        String text = "Heading\n\nA|B\nC|D";
        int tableStart = text.indexOf("A|B");
        int tableEnd = text.length();
        var payload = canonicalPayload(text,
            heading("Heading", 1, "h1", 0, 7),
            withRange(table("t1", List.of()), tableStart, tableEnd));

        var table = chunks(payload, CanonicalTextEvidence.from(payload)).stream()
            .filter(c -> c.type() == SemanticChunk.ChunkType.TABLE).findFirst().orElseThrow();

        assertThat(table.documentOffsetStart()).isEqualTo(tableStart);
        assertThat(table.documentOffsetEnd()).isEqualTo(tableEnd);
    }

    @Test
    void benchmarkTextPolicyUsesHeadingOnceAndNoBreadcrumb() {
        var payload = DocumentPayload.newBuilder()
            .addElements(heading("Section", 1, "h1"))
            .addElements(paragraph("p1", "Body"))
            .build();

        var chunks = chunks(payload, CanonicalTextEvidence.absent());
        var body = chunks.stream().filter(c -> c.type() == SemanticChunk.ChunkType.SECTION)
            .findFirst().orElseThrow();

        assertThat(body.content()).isEqualTo("Section\n\nBody");
        assertThat(body.content()).doesNotContain("Section > Section");
    }

    @Test
    void defaultRetrievalPolicyStillIncludesBreadcrumb() {
        var payload = DocumentPayload.newBuilder()
            .addElements(heading("Section", 1, "h1"))
            .addElements(paragraph("p1", "Body"))
            .build();
        var sections = builder.build(payload.getElementsList());
        var chunks = chunker.chunk(sections, "doc", ChunkerConfig.defaults());

        assertThat(chunks).anySatisfy(c -> {
            assertThat(c.content()).startsWith("Section\n\nBody");
            assertThat(c.sectionPath()).containsExactly("Section");
        });
    }

    @Test
    void canonicalRangesSetContiguousOffsets() {
        String text = "Title\n\nBody";
        var payload = canonicalPayload(text,
            heading("Title", 1, "h1", 0, 5),
            paragraph("p1", "Body", 7, 11));

        var chunks = chunks(payload, CanonicalTextEvidence.from(payload));
        var body = chunks.stream().filter(c -> c.type() == SemanticChunk.ChunkType.SECTION)
            .findFirst().orElseThrow();

        assertThat(body.documentOffsetStart()).isZero();
        assertThat(body.documentOffsetEnd()).isEqualTo(11);
    }

    @Test
    void missingCanonicalEvidenceRemainsExplicitlyUnmapped() {
        var payload = DocumentPayload.newBuilder()
            .addElements(heading("Title", 1, "h1"))
            .addElements(paragraph("p1", "Body"))
            .build();

        var chunks = chunks(payload, CanonicalTextEvidence.absent());
        var body = chunks.stream().filter(c -> c.type() == SemanticChunk.ChunkType.SECTION)
            .findFirst().orElseThrow();

        assertThat(body.documentOffsetStart()).isEqualTo(-1);
        assertThat(body.metadata()).containsEntry("evidence_status", "CANONICAL_EVIDENCE_UNAVAILABLE");
    }

    @Test
    void discontiguousCanonicalEvidenceIsNotMinMax() {
        String text = "Title\n\nskip\n\nBody";
        var payload = canonicalPayload(text,
            heading("Title", 1, "h1", 0, 5),
            paragraph("p1", "Body", 13, 17));

        var chunks = chunks(payload, CanonicalTextEvidence.from(payload));
        var body = chunks.stream().filter(c -> c.type() == SemanticChunk.ChunkType.SECTION)
            .findFirst().orElseThrow();

        assertThat(body.documentOffsetStart()).isEqualTo(-1);
        assertThat(body.metadata()).containsEntry("evidence_status", "DISCONTIGUOUS_SOURCE_EVIDENCE");
    }

    @Test
    void containerChunkUsesCanonicalPreambleSlice() {
        String text = "Book\n\nAuthor: Alex\n\nPreface\n\nBody";
        int prefaceStart = text.indexOf("Preface");
        var payload = canonicalPayload(text,
            heading("Book", 1, "h1", 0, 4),
            heading("Preface", 2, "h2", prefaceStart, prefaceStart + 7),
            paragraph("p1", "Body", text.indexOf("Body"), text.indexOf("Body") + 4));

        var chunks = chunks(payload, CanonicalTextEvidence.from(payload));
        var container = chunks.stream().filter(c -> c.type() == SemanticChunk.ChunkType.HEADING)
            .findFirst().orElseThrow();

        assertThat(container.content()).isEqualTo("Book\n\nAuthor: Alex\n\n");
        assertThat(container.documentOffsetStart()).isZero();
        assertThat(container.documentOffsetEnd()).isEqualTo(prefaceStart);
    }

    private List<SemanticChunk> chunks(DocumentPayload payload, CanonicalTextEvidence canonical) {
        var sections = builder.build(payload.getElementsList());
        return chunker.chunk(sections, "doc", ChunkerConfig.benchmarkV1(),
            ChunkTextPolicy.BENCHMARK_V1, canonical);
    }

    private static DocumentElement heading(String text, int level, String id) {
        return DocumentElement.newBuilder().setId(id).setType(DocumentElementType.ELEMENT_HEADING)
            .setText(text).setLevel(level).build();
    }

    private static DocumentElement heading(String text, int level, String id, int start, int end) {
        return withRange(heading(text, level, id), start, end);
    }

    private static DocumentElement paragraph(String id, String text) {
        return DocumentElement.newBuilder().setId(id).setType(DocumentElementType.ELEMENT_PARAGRAPH)
            .setText(text).build();
    }

    private static DocumentElement paragraph(String id, String text, int start, int end) {
        return withRange(paragraph(id, text), start, end);
    }

    private static DocumentElement table(String id, List<String> children) {
        return DocumentElement.newBuilder().setId(id).setType(DocumentElementType.ELEMENT_TABLE)
            .setText("table").addAllChildIds(children).build();
    }

    private static DocumentElement row(String id, List<String> children) {
        return DocumentElement.newBuilder().setId(id).setType(DocumentElementType.ELEMENT_TABLE_ROW)
            .setText("row").addAllChildIds(children).build();
    }

    private static DocumentElement cell(String id, String text) {
        return DocumentElement.newBuilder().setId(id).setType(DocumentElementType.ELEMENT_TABLE_CELL)
            .setText(text).build();
    }

    private static DocumentElement withRange(DocumentElement element, int start, int end) {
        return element.toBuilder().setCanonicalTextRange(range(start, end, "source-digest", "")).build();
    }

    private static DocumentPayload canonicalPayload(String text, DocumentElement... elements) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        String digest = sha256(bytes);
        String sourceDigest = "source-digest";
        var payload = DocumentPayload.newBuilder()
            .setMediaType("text/markdown")
            .setSource(SourceDescriptor.newBuilder().setMediaType("text/markdown")
                .setSourceFormat("markdown").setSourceDigest(sourceDigest).setSourceVersion("v1"))
            .addProjections(DocumentProjection.newBuilder()
                .setProjectionId("canonical-text")
                .setContractId("synanton.canonical-text")
                .setContractVersion("1")
                .setFormat("text/plain; charset=utf-8")
                .setOutcome(ProjectionOutcome.PROJECTION_OUTCOME_SUCCESS_LOSSLESS)
                .setDigest("sha256:" + digest)
                .setInlineContent(ByteString.copyFrom(bytes)));
        for (DocumentElement element : elements) {
            DocumentElement.Builder b = element.toBuilder();
            if (element.hasCanonicalTextRange()) {
                b.setCanonicalTextRange(range(
                    (int) element.getCanonicalTextRange().getStart(),
                    (int) element.getCanonicalTextRange().getEnd(), sourceDigest, digest));
            }
            payload.addElements(b);
        }
        return payload.build();
    }

    private static CanonicalTextRange range(int start, int end, String sourceDigest, String textDigest) {
        return CanonicalTextRange.newBuilder()
            .setIdentity(CanonicalTextIdentity.newBuilder()
                .setCanonicalTextId("ct-v1:sha256:test")
                .setCanonicalTextDigest("sha256:" + textDigest)
                .setEncoding("UTF-8")
                .setSourceDocumentId("doc")
                .setSourceDigest(sourceDigest)
                .setSourceVersion("v1")
                .setNormalizationProfile("p1-markdown")
                .setNormalizationVersion("1")
                .setProjectionContractId("synanton.canonical-text")
                .setProjectionContractVersion("1")
                .setLineEndings(LineEndingPolicy.LINE_ENDING_LF)
                .setNormalization(TextNormalization.TEXT_NORMALIZATION_NFC))
            .setUnit(OffsetUnit.OFFSET_UNIT_UTF16_CODE_UNIT)
            .setStart(start).setEnd(end)
            .setNormalization(TextNormalization.TEXT_NORMALIZATION_NFC)
            .setLineEndings(LineEndingPolicy.LINE_ENDING_LF)
            .build();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
