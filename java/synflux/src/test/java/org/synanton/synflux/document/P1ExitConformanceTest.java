package org.synanton.synflux.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.client.ExtractionClientMetrics;
import org.synanton.extraction.client.ExtractionClientProperties;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.ExtractionResult;
import org.synanton.extraction.v1.ExtractionStatus;
import org.synanton.extraction.v1.PriorityClass;
import org.synanton.extraction.v1.StructuredPayload;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        String artifactDigest = StructuralArtifactDigest.sha256(first);
        assertThat(artifactDigest)
            .isEqualTo(StructuralArtifactDigest.sha256(second))
            .isEqualTo("f6137a158d6a4f5fd45bca4b75033e675435fe6bd00098f478d6cada6cb63147");
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/p1-exit"));
        java.nio.file.Files.writeString(
            java.nio.file.Path.of("build/p1-exit/structural-artifact-digest.txt"), artifactDigest);

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

        ParsedDocument parsed = extractionStage().apply(
            acquired, new StageContext("tenant-p1", "job-p1", null)
                .withSourceVersionId(UUID.randomUUID()));

        assertThat(parsed.structure()).isNull();
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
        ExtractionPlaneClient client = mock(ExtractionPlaneClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.defaultPriorityClass()).thenReturn(PriorityClass.PRIORITY_NORMAL);
        DocumentPayload payload = DocumentPayload.newBuilder()
            .setFlattenedText("Body paragraph.")
            .build();
        when(client.extract(any())).thenReturn(ExtractionResult.newBuilder()
            .setStatus(ExtractionStatus.STATUS_COMPLETED)
            .setFlattenedText("Body paragraph.")
            .setPayload(StructuredPayload.newBuilder().setInlineContent(payload.toByteString()))
            .build());
        return new ExtractionStage(client, mock(ObjectStorePort.class), "synanton-hot");
    }
}
