package org.synanton.synflux.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.client.ExtractionClientMetrics;
import org.synanton.extraction.client.ExtractionClientProperties;
import org.synanton.extraction.client.ExtractionFallbackPolicy;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.client.LocalTikaFallbackExtractor;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.stage.ExtractionStage;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ObjectStorePort;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Formal P1 exit conformance run on the synthetic CC0 fixture. This is not the independent A2
 * evaluation fixture; it verifies the structural boundary and its evidence resolution.
 */
class P1ExitConformanceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rightsClearedFixturePassesStructuralAndExtractionBoundaryConformance() throws Exception {
        byte[] markdown = resource("fixtures/p1-cc0-it-books.md");
        JsonNode annotations = mapper.readTree(resource("fixtures/p1-cc0-conformance.json"));
        AcquiredDocument acquired = acquired(markdown);
        SourceDocument source = new SourceDocument(
            "tenant-p1", "p1-cc0", "version-1", acquired.contentRefId().toString(),
            "text/markdown", markdown);

        DocumentStructure first = DocumentStructureService.defaults().process(source);
        DocumentStructure second = DocumentStructureService.defaults().process(source);
        assertThat(ids(first)).containsExactlyElementsOf(ids(second));

        int resolved = 0;
        for (JsonNode expected : annotations.path("expected")) {
            String text = expected.path("text").asText();
            DocumentStructure.Element element = first.elements().stream()
                .filter(e -> e.text().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected element not found: " + text));
            assertThat(element.elementType().name()).isEqualTo(expected.path("elementType").asText());
            assertThat(element.geometryStatus().name()).isEqualTo(expected.path("geometry").asText());
            assertThat(element.parserName()).isEqualTo("Synanton Markdown Adapter");
            assertThat(element.parserVersion()).isNotBlank();
            StructuralEvidenceResolver.Evidence evidence = new StructuralEvidenceResolver()
                .resolveDocumentSpan(first, element.documentStartOffset(), element.documentEndOffset());
            assertThat(evidence.text()).isEqualTo(text);
            assertThat(evidence.elements()).isNotEmpty();
            resolved++;
        }
        assertThat(resolved).isEqualTo(annotations.path("expected").size());

        UUID sourceVersion = UUID.randomUUID();
        ParsedDocument parsed = extractionStage().apply(
            acquired, new StageContext("tenant-p1", "job-p1", null)
                .withSourceVersionId(sourceVersion));

        assertThat(parsed.structure()).isNotNull();
        assertThat(ids(parsed.structure())).containsExactlyElementsOf(ids(first));
        assertThat(parsed.metadata())
            .containsEntry("structural_status", "PARSED")
            .containsEntry("structural_parser_name", "Synanton Markdown Adapter")
            .containsEntry("structural_normalization_version", "NFC_UTF16_V1")
            .containsEntry("structural_chunking_config", "markdown-heading-v1")
            .containsEntry("structural_source_version_id", sourceVersion.toString())
            .containsEntry("text_projection", "EXTRACTION_TEXT");
    }

    private static List<String> ids(DocumentStructure structure) {
        return structure.elements().stream().map(DocumentStructure.Element::elementId).toList();
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream input = P1ExitConformanceTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return input.readAllBytes();
        }
    }

    private static AcquiredDocument acquired(byte[] bytes) {
        ContentRef ref = new ContentRef("file", "file:///p1-cc0.md", "text/markdown",
            bytes.length, Instant.now());
        return new AcquiredDocument(ref, bytes, "sha-p1", "text/markdown", ref.uri(),
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    }

    private static ExtractionStage extractionStage() {
        ExtractionClientProperties props = new ExtractionClientProperties(
            false, "localhost:9091", "sync", 120, 5, "local-tika", "NORMAL");
        ExtractionPlaneClient client = new ExtractionPlaneClient(props,
            new ExtractionClientMetrics(null));
        return new ExtractionStage(
            client, new LocalTikaFallbackExtractor(),
            ExtractionFallbackPolicy.FALLBACK_LOCAL_TIKA,
            new ExtractionClientMetrics(new SimpleMeterRegistry()),
            mock(ObjectStorePort.class), "synanton-hot");
    }
}
