package org.synanton.synflux.document;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural conformance for the synthetic CC0 IT Books excerpt. This is not the A2 evaluation
 * fixture and makes no quality/entity judgments.
 */
class P1FixtureConformanceTest {

    private static final String FIXTURE = "fixtures/p1-cc0-it-books.md";

    @Test
    void fixtureConformsAcrossTwoRuns() throws Exception {
        byte[] bytes = fixture();
        DocumentStructureService service = DocumentStructureService.defaults();
        SourceDocument source = new SourceDocument(
            "tenant-a", "source-p1", "version-p1", "doc-p1", "text/markdown", bytes);

        DocumentStructure first = service.process(source);
        DocumentStructure second = service.process(source);

        assertThat(first.parserName()).isEqualTo("Synanton Markdown Adapter");
        assertThat(first.parserVersion()).isNotBlank();
        assertThat(first.normalizationVersion()).isEqualTo("NFC_UTF16_V1");
        assertThat(first.chunkingConfig()).isEqualTo("markdown-heading-v1");
        assertThat(first.sourceId()).isEqualTo("source-p1");
        assertThat(first.sourceVersionId()).isEqualTo("version-p1");

        assertThat(ids(first)).containsExactlyElementsOf(ids(second));
        assertThat(chunkIds(first)).containsExactlyElementsOf(chunkIds(second));

        for (DocumentStructure.Element element : first.elements()) {
            assertThat(element.geometryStatus()).isEqualTo(DocumentStructure.GeometryStatus.NOT_APPLICABLE);
            assertThat(element.boundingBox()).isNull();
            assertThat(element.parserName()).isEqualTo(first.parserName());
            assertThat(first.text().substring(element.documentStartOffset(), element.documentEndOffset()))
                .isEqualTo(element.text());
            DocumentStructure.Chunk chunk = first.chunks().stream()
                .filter(c -> c.chunkId().equals(element.chunkId()))
                .findFirst().orElseThrow();
            assertThat(chunk.text().substring(element.chunkStartOffset(), element.chunkEndOffset()))
                .isEqualTo(element.text());
        }
        for (DocumentStructure.Chunk chunk : first.chunks()) {
            assertThat(first.text().substring(chunk.documentStartOffset(), chunk.documentEndOffset()))
                .isEqualTo(chunk.text());
        }

        StructuralEvidenceResolver.Evidence evidence = new StructuralEvidenceResolver()
            .resolveDocumentSpan(first, first.text().indexOf("A page is"),
                first.text().indexOf("A page is") + "A page is".length());
        assertThat(evidence.text()).isEqualTo("A page is");
        assertThat(evidence.elements()).isNotEmpty();
        assertThat(evidence.elements().getFirst().geometryStatus())
            .isEqualTo(DocumentStructure.GeometryStatus.NOT_APPLICABLE);
    }

    private static List<String> ids(DocumentStructure structure) {
        return structure.elements().stream().map(DocumentStructure.Element::elementId).toList();
    }

    private static List<String> chunkIds(DocumentStructure structure) {
        return structure.chunks().stream().map(DocumentStructure.Chunk::chunkId).toList();
    }

    private static byte[] fixture() throws Exception {
        try (InputStream input = P1FixtureConformanceTest.class.getClassLoader().getResourceAsStream(FIXTURE)) {
            assertThat(input).as("fixture resource " + FIXTURE).isNotNull();
            return input.readAllBytes();
        }
    }
}
