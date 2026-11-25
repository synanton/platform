package org.synanton.synflux.document.evidence;

import org.junit.jupiter.api.Test;
import org.synanton.synflux.document.DocumentStructure;
import org.synanton.synflux.document.DocumentStructureService;
import org.synanton.synflux.document.SourceDocument;
import org.synanton.synflux.document.StructuralArtifactDigest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceBinderTest {

    private final EvidenceBinder binder = new EvidenceBinder();

    @Test
    void bindsStructuralDocumentAndChunkSpansBackToElements() {
        DocumentStructure structure = markdown("# Title\n\nBody text here.\n");
        DocumentStructure.Element paragraph = structure.elements().stream()
            .filter(e -> e.text().startsWith("Body")).findFirst().orElseThrow();
        EvidenceBinder.Mapping mapping = mapping(structure, structure.text(), List.of());

        EvidenceBinder.Result document = binder.bind(structure, structure.text(), mapping,
            new EvidenceBinder.Candidate("candidate-doc", EvidenceBinder.Space.STRUCTURAL_DOCUMENT,
                paragraph.documentStartOffset(), paragraph.documentEndOffset(), null,
                EvidenceBinder.Grounding.EXACT, "template", "compilation"));

        assertThat(document.status()).isEqualTo(EvidenceBinder.Status.RESOLVED);
        assertThat(document.evidence().text()).isEqualTo(paragraph.text());
        assertThat(document.evidence().elementIds()).containsExactly(paragraph.elementId());
        assertThat(document.evidence().chunkIds()).containsExactly(paragraph.chunkId());
        assertThat(document.evidence().geometry().status())
            .isEqualTo(DocumentStructure.GeometryStatus.NOT_APPLICABLE);
        assertThat(document.evidence().inputSpace()).isEqualTo(EvidenceBinder.Space.STRUCTURAL_DOCUMENT);
        assertThat(binder.bind(structure, structure.text(), mapping,
            new EvidenceBinder.Candidate("candidate-doc", EvidenceBinder.Space.STRUCTURAL_DOCUMENT,
                paragraph.documentStartOffset(), paragraph.documentEndOffset(), null,
                EvidenceBinder.Grounding.EXACT, "template", "compilation"))).isEqualTo(document);

        DocumentStructure.Chunk chunk = structure.chunks().stream()
            .filter(c -> c.chunkId().equals(paragraph.chunkId())).findFirst().orElseThrow();
        int start = paragraph.documentStartOffset() - chunk.documentStartOffset();
        int end = paragraph.documentEndOffset() - chunk.documentStartOffset();
        EvidenceBinder.Result chunkResult = binder.bind(structure, structure.text(), mapping,
            new EvidenceBinder.Candidate("candidate-chunk", EvidenceBinder.Space.STRUCTURAL_CHUNK,
                start, end, chunk.chunkId(), EvidenceBinder.Grounding.EXACT, "template", "compilation"));
        assertThat(chunkResult.status()).isEqualTo(EvidenceBinder.Status.RESOLVED);
        assertThat(chunkResult.evidence().documentStartOffset()).isEqualTo(paragraph.documentStartOffset());
    }

    @Test
    void mapsExtractionInputSpansAndPreservesApproximateGrounding() {
        DocumentStructure structure = markdown("# Title\n\nBody text here.\n");
        DocumentStructure.Chunk chunk = structure.chunks().getFirst();
        String extractionText = structure.text();
        int inputStart = chunk.documentStartOffset();
        int inputEnd = chunk.documentEndOffset();
        EvidenceBinder.Mapping mapping = mapping(structure, extractionText, List.of(
            new EvidenceBinder.Segment(inputStart, inputEnd, chunk.chunkId(), 0,
                chunk.text().length(), EvidenceBinder.Grounding.APPROXIMATE)));

        EvidenceBinder.Result result = binder.bind(structure, extractionText, mapping,
            new EvidenceBinder.Candidate("candidate-extraction", EvidenceBinder.Space.EXTRACTION_INPUT,
                inputStart, inputEnd, null, EvidenceBinder.Grounding.EXACT, "template", "compilation"));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.RESOLVED);
        assertThat(result.evidence().grounding()).isEqualTo(EvidenceBinder.Grounding.APPROXIMATE);
        assertThat(result.evidence().inputGrounding()).isEqualTo(EvidenceBinder.Grounding.EXACT);
        assertThat(result.evidence().mappingDigest()).isEqualTo(mapping.digest());
        assertThat(result.evidence().text()).isEqualTo(structure.text()
            .substring(chunk.documentStartOffset(), chunk.documentEndOffset()));
    }

    @Test
    void keepsExplicitDocumentScopeDistinctFromFailedSpanBinding() {
        DocumentStructure structure = markdown("# Title\n\nBody text here.\n");
        EvidenceBinder.Mapping mapping = mapping(structure, structure.text(), List.of());

        EvidenceBinder.Result scoped = binder.bind(structure, structure.text(), mapping,
            new EvidenceBinder.Candidate("doc-scope", EvidenceBinder.Space.STRUCTURAL_DOCUMENT,
                0, 0, null, EvidenceBinder.Grounding.DOCUMENT_SCOPE, "template", "compilation"));
        assertThat(scoped.status()).isEqualTo(EvidenceBinder.Status.RESOLVED);
        assertThat(scoped.evidence().grounding()).isEqualTo(EvidenceBinder.Grounding.DOCUMENT_SCOPE);
        assertThat(scoped.evidence().elementIds()).isEmpty();
        assertThat(scoped.evidence().geometry().status())
            .isEqualTo(DocumentStructure.GeometryStatus.NOT_APPLICABLE);

        EvidenceBinder.Result bad = binder.bind(structure, structure.text(), mapping,
            new EvidenceBinder.Candidate("bad", EvidenceBinder.Space.STRUCTURAL_DOCUMENT,
                0, structure.text().length() + 1, null, EvidenceBinder.Grounding.EXACT,
                "template", "compilation"));
        assertThat(bad.status()).isEqualTo(EvidenceBinder.Status.MISBOUND);
        assertThat(bad.evidence()).isNull();
    }

    @Test
    void failsClosedForMissingPartialAndOutOfRangeSpans() {
        DocumentStructure structure = markdown("# Title\n\nBody text here.\n");
        String extractionText = structure.text();
        EvidenceBinder.Mapping mapping = mapping(structure, extractionText, List.of(
            new EvidenceBinder.Segment(0, 5, structure.chunks().getFirst().chunkId(), 0, 5,
                EvidenceBinder.Grounding.EXACT)));

        EvidenceBinder.Result missing = binder.bind(structure, extractionText, mapping,
            extractionCandidate(7, 8));
        assertThat(missing.status()).isEqualTo(EvidenceBinder.Status.UNMAPPED);
        assertThat(missing.reason()).isEqualTo("NO_MAPPING_SEGMENT");

        EvidenceBinder.Result partial = binder.bind(structure, extractionText, mapping,
            extractionCandidate(3, 8));
        assertThat(partial.status()).isEqualTo(EvidenceBinder.Status.UNMAPPED);
        assertThat(partial.reason()).isEqualTo("PARTIAL_MAPPING");

        EvidenceBinder.Result outOfRange = binder.bind(structure, extractionText, mapping,
            new EvidenceBinder.Candidate("bad", EvidenceBinder.Space.STRUCTURAL_DOCUMENT,
                0, extractionText.length() + 1, null, EvidenceBinder.Grounding.EXACT,
                "template", "compilation"));
        assertThat(outOfRange.status()).isEqualTo(EvidenceBinder.Status.MISBOUND);
        assertThat(outOfRange.reason()).isEqualTo("STRUCTURAL_SPAN_OUT_OF_RANGE");

        EvidenceBinder.Result unknownChunk = binder.bind(structure, extractionText, mapping,
            new EvidenceBinder.Candidate("bad-chunk", EvidenceBinder.Space.STRUCTURAL_CHUNK,
                0, 1, "missing", EvidenceBinder.Grounding.EXACT, "template", "compilation"));
        assertThat(unknownChunk.status()).isEqualTo(EvidenceBinder.Status.MISBOUND);
        assertThat(unknownChunk.reason()).isEqualTo("UNKNOWN_CHUNK");
    }

    @Test
    void resolvesCrossChunkSpansAndKeepsChunkIdentity() {
        DocumentStructure structure = twoChunkPdfLikeStructure();
        EvidenceBinder.Mapping mapping = mapping(structure, structure.text(), List.of());
        EvidenceBinder.Result result = binder.bind(structure, structure.text(), mapping,
            new EvidenceBinder.Candidate("cross", EvidenceBinder.Space.STRUCTURAL_DOCUMENT,
                1, 5, null, EvidenceBinder.Grounding.EXACT, "template", "compilation"));

        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.RESOLVED);
        assertThat(result.evidence().chunkIds()).containsExactly("chunk-1", "chunk-2");
        assertThat(result.evidence().elementIds()).containsExactly("element-1", "element-2");
        assertThat(result.evidence().geometry().status())
            .isEqualTo(DocumentStructure.GeometryStatus.AVAILABLE);
        assertThat(result.evidence().geometry().boundingBox())
            .isEqualTo(new EvidenceBinder.CanonicalBox("0.100000", "0.200000", "2.000000", "2.000000"));
    }

    @Test
    void rejectsAmbiguousOrDiscontinuousMappings() {
        DocumentStructure structure = twoChunkPdfLikeStructure();
        String extractionText = structure.text();
        EvidenceBinder.Mapping mapping = mapping(structure, extractionText, List.of(
            new EvidenceBinder.Segment(0, 3, "chunk-1", 0, 3, EvidenceBinder.Grounding.EXACT),
            new EvidenceBinder.Segment(3, 6, "chunk-1", 0, 3, EvidenceBinder.Grounding.EXACT)));

        EvidenceBinder.Result result = binder.bind(structure, extractionText, mapping,
            new EvidenceBinder.Candidate("ambiguous", EvidenceBinder.Space.EXTRACTION_INPUT,
                0, 6, null, EvidenceBinder.Grounding.EXACT, "template", "compilation"));
        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.MISBOUND);
        assertThat(result.reason()).isEqualTo("NON_CONTIGUOUS_MAPPING");
    }

    @Test
    void canonicalizesBBoxesAndKeepsGeometryStatesDistinct() {
        EvidenceBinder.CanonicalBox box = EvidenceBinder.canonicalBox(
            new DocumentStructure.BoundingBox(0.1234567f, -0.0f, 1.0f, 2.0f));
        assertThat(box).isEqualTo(new EvidenceBinder.CanonicalBox(
            "0.123457", "0.000000", "1.000000", "2.000000"));
        assertThatThrownBy(() -> EvidenceBinder.canonicalBox(
            new DocumentStructure.BoundingBox(Float.NaN, 0, 1, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("finite");
        assertThatThrownBy(() -> EvidenceBinder.canonicalBox(
            new DocumentStructure.BoundingBox(0, 0, Float.POSITIVE_INFINITY, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("finite");

        DocumentStructure available = singleElementStructure(
            DocumentStructure.GeometryStatus.AVAILABLE, DocumentStructure.ElementType.PARAGRAPH);
        DocumentStructure unavailable = singleElementStructure(
            DocumentStructure.GeometryStatus.UNAVAILABLE, DocumentStructure.ElementType.PARAGRAPH);
        DocumentStructure notApplicable = singleElementStructure(
            DocumentStructure.GeometryStatus.NOT_APPLICABLE, DocumentStructure.ElementType.PARAGRAPH);

        assertThat(bindSingle(available).evidence().geometry().status())
            .isEqualTo(DocumentStructure.GeometryStatus.AVAILABLE);
        assertThat(bindSingle(available).evidence().geometry().pageNumber()).isEqualTo(1);
        assertThat(bindSingle(available).evidence().geometry().coordinateSystem()).isEqualTo("TEST_TOP_LEFT");
        assertThat(bindSingle(available).evidence().geometry().unit()).isEqualTo("POINT");
        assertThat(bindSingle(available).evidence().geometry().boundingBox().x0()).isEqualTo("0.500000");
        assertThat(bindSingle(unavailable).evidence().geometry().status())
            .isEqualTo(DocumentStructure.GeometryStatus.UNAVAILABLE);
        assertThat(bindSingle(unavailable).evidence().geometry().boundingBox()).isNull();
        assertThat(bindSingle(notApplicable).evidence().geometry().status())
            .isEqualTo(DocumentStructure.GeometryStatus.NOT_APPLICABLE);
        assertThat(bindSingle(notApplicable).evidence().geometry().boundingBox()).isNull();
    }

    @Test
    void offsetSpansAreUtf16AndMappingIsRepeatable() {
        DocumentStructure structure = singleElementStructure(
            DocumentStructure.GeometryStatus.NOT_APPLICABLE, DocumentStructure.ElementType.PARAGRAPH);
        EvidenceBinder.Mapping first = mapping(structure, structure.text(), List.of());
        EvidenceBinder.Mapping second = mapping(structure, structure.text(), List.of());

        assertThat(first.digest()).isEqualTo(second.digest());
        EvidenceBinder.Result result = binder.bind(structure, structure.text(), first,
            new EvidenceBinder.Candidate("emoji", EvidenceBinder.Space.STRUCTURAL_DOCUMENT,
                1, 3, null, EvidenceBinder.Grounding.EXACT, "template", "compilation"));
        assertThat(result.status()).isEqualTo(EvidenceBinder.Status.RESOLVED);
        assertThat(result.evidence().text()).isEqualTo("😀");
    }

    @Test
    void reportAccountsForEveryOutcomeWithoutSilentDrops() {
        DocumentStructure structure = markdown("# Title\n\nBody text here.\n");
        String text = structure.text();
        EvidenceBinder.Mapping mapping = mapping(structure, text, List.of());
        EvidenceBinder.Result resolved = binder.bind(structure, text, mapping,
            new EvidenceBinder.Candidate("ok", EvidenceBinder.Space.STRUCTURAL_DOCUMENT,
                0, 1, null, EvidenceBinder.Grounding.EXACT, "template", "compilation"));
        EvidenceBinder.Result unmapped = binder.bind(structure, text, mapping,
            new EvidenceBinder.Candidate("gap", EvidenceBinder.Space.EXTRACTION_INPUT,
                0, 1, null, EvidenceBinder.Grounding.EXACT, "template", "compilation"));
        EvidenceBinder.Result misbound = binder.bind(structure, text, mapping,
            new EvidenceBinder.Candidate("bad", EvidenceBinder.Space.STRUCTURAL_DOCUMENT,
                0, text.length() + 1, null, EvidenceBinder.Grounding.EXACT, "template", "compilation"));

        EvidenceBinder.Report report = EvidenceBinder.Report.from(List.of(resolved, unmapped, misbound));
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.resolved()).isEqualTo(1);
        assertThat(report.unmapped()).isEqualTo(1);
        assertThat(report.misbound()).isEqualTo(1);
        assertThat(report.silentDrops()).isZero();
        assertThat(report.silentDropRate()).isZero();
        assertThat(report.misbindingRate()).isEqualTo(1.0 / 3.0);
        assertThat(report.failureReasons()).containsKeys("NO_MAPPING_SEGMENT", "STRUCTURAL_SPAN_OUT_OF_RANGE");
    }

    private EvidenceBinder.Result bindSingle(DocumentStructure structure) {
        return binder.bind(structure, structure.text(), mapping(structure, structure.text(), List.of()),
            new EvidenceBinder.Candidate("single", EvidenceBinder.Space.STRUCTURAL_DOCUMENT,
                0, structure.text().length(), null, EvidenceBinder.Grounding.EXACT,
                "template", "compilation"));
    }

    private static EvidenceBinder.Candidate extractionCandidate(int start, int end) {
        return new EvidenceBinder.Candidate("extraction", EvidenceBinder.Space.EXTRACTION_INPUT,
            start, end, null, EvidenceBinder.Grounding.EXACT, "template", "compilation");
    }

    private static DocumentStructure markdown(String text) {
        return DocumentStructureService.defaults().process(new SourceDocument(
            "tenant", "source", "version", "doc-md", "text/markdown",
            text.getBytes(StandardCharsets.UTF_8)));
    }

    private static EvidenceBinder.Mapping mapping(DocumentStructure structure, String extractionText,
                                                  List<EvidenceBinder.Segment> segments) {
        return new EvidenceBinder.Mapping(sha256(extractionText),
            StructuralArtifactDigest.sha256(structure), structure.chunkingConfig(),
            "template", "compilation", segments);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DocumentStructure singleElementStructure(
            DocumentStructure.GeometryStatus geometry, DocumentStructure.ElementType type) {
        String text = "A😀B";
        DocumentStructure.BoundingBox box = geometry == DocumentStructure.GeometryStatus.AVAILABLE
            ? new DocumentStructure.BoundingBox(0.5f, 0.25f, 10.0f, 20.0f) : null;
        List<DocumentStructure.Page> pages = geometry == DocumentStructure.GeometryStatus.AVAILABLE
            ? List.of(new DocumentStructure.Page(1, 100, 200, "TEST_TOP_LEFT", "POINT"))
            : List.of();
        DocumentStructure.Element element = new DocumentStructure.Element(
            "element-1", geometry == DocumentStructure.GeometryStatus.AVAILABLE ? 1 : null,
            type, 0, "chunk-1", 0, text.length(), 0, text.length(), box, geometry, text,
            "parser", "1");
        DocumentStructure.Chunk chunk = new DocumentStructure.Chunk(
            "chunk-1", 0, "", 0, text.length(), text, List.of("element-1"));
        return new DocumentStructure("tenant", "source", "version", "doc-single", "text/markdown",
            "NFC_UTF16_V1", "test-chunking", "parser", "1", text, pages, List.of(element), List.of(chunk));
    }

    private static DocumentStructure twoChunkPdfLikeStructure() {
        String text = "012345";
        List<DocumentStructure.Page> pages = List.of(
            new DocumentStructure.Page(1, 100, 200, "TEST_TOP_LEFT", "POINT"));
        DocumentStructure.Element e1 = new DocumentStructure.Element(
            "element-1", 1, DocumentStructure.ElementType.PARAGRAPH, 0, "chunk-1",
            0, 3, 0, 3, new DocumentStructure.BoundingBox(0.1f, 0.2f, 1f, 2f),
            DocumentStructure.GeometryStatus.AVAILABLE, "012", "parser", "1");
        DocumentStructure.Element e2 = new DocumentStructure.Element(
            "element-2", 1, DocumentStructure.ElementType.PARAGRAPH, 1, "chunk-2",
            0, 3, 3, 6, new DocumentStructure.BoundingBox(1.1f, 0.2f, 2f, 2f),
            DocumentStructure.GeometryStatus.AVAILABLE, "345", "parser", "1");
        DocumentStructure.Chunk c1 = new DocumentStructure.Chunk(
            "chunk-1", 0, "", 0, 3, "012", List.of("element-1"));
        DocumentStructure.Chunk c2 = new DocumentStructure.Chunk(
            "chunk-2", 1, "", 3, 6, "345", List.of("element-2"));
        return new DocumentStructure("tenant", "source", "version", "doc-two", "application/pdf",
            "NFC_UTF16_V1", "pdf-page-v1", "parser", "1", text, pages,
            List.of(e1, e2), List.of(c1, c2));
    }
}
