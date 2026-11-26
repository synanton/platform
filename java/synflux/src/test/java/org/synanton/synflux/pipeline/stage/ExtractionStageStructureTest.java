package org.synanton.synflux.pipeline.stage;

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
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ObjectStorePort;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExtractionStageStructureTest {

    @Test
    void attachesParserNeutralStructureAndMetadataToExtraction() {
        String markdown = "# Title\n\nBody paragraph.\n";
        ExtractionStage stage = stage();
        UUID versionId = UUID.randomUUID();
        AcquiredDocument doc = document(markdown.getBytes(StandardCharsets.UTF_8), "text/markdown");

        ParsedDocument parsed = stage.apply(doc,
            new StageContext("demo", "job", null).withSourceVersionId(versionId));

        assertThat(parsed.text()).contains("Body paragraph.");
        assertThat(parsed.structure()).isNotNull();
        assertThat(parsed.structure().parserName()).isEqualTo("Synanton Markdown Adapter");
        assertThat(parsed.structure().chunkingConfig()).isEqualTo("markdown-heading-v1");
        assertThat(parsed.structure().sourceVersionId()).isEqualTo(versionId.toString());
        assertThat(parsed.metadata())
            .containsEntry("structural_status", "PARSED")
            .containsEntry("structural_parser_name", "Synanton Markdown Adapter")
            .containsEntry("structural_normalization_version", "NFC_UTF16_V1")
            .containsEntry("structural_chunking_config", "markdown-heading-v1")
            .containsEntry("structural_source_version_id", versionId.toString())
            .containsEntry("text_projection", "EXTRACTION_TEXT");
        assertThat(parsed.metadata().get("structural_text_sha256")).hasSize(64);
        assertThat(parsed.metadata().get("extraction_text_sha256")).hasSize(64);
        assertThat(parsed.structure().elements()).allSatisfy(element -> {
            assertThat(parsed.structure().text()
                .substring(element.documentStartOffset(), element.documentEndOffset()))
                .isEqualTo(element.text());
            assertThat(element.parserVersion()).isNotBlank();
        });
    }

    @Test
    void marksUnsupportedFormatsExplicitlyWithoutInventingStructure() {
        ExtractionStage stage = stage();
        AcquiredDocument doc = document("plain text".getBytes(StandardCharsets.UTF_8), "text/plain");

        ParsedDocument parsed = stage.apply(doc,
            new StageContext("demo", "job", null).withSourceVersionId(UUID.randomUUID()));

        assertThat(parsed.structure()).isNull();
        assertThat(parsed.metadata()).containsEntry("structural_status", "UNSUPPORTED");
    }

    private static ExtractionStage stage() {
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

    private static AcquiredDocument document(byte[] bytes, String mediaType) {
        ContentRef ref = new ContentRef("file", "file:///tmp/a.md", mediaType, bytes.length, Instant.now());
        return new AcquiredDocument(ref, bytes, "sha", mediaType, "file:///tmp/a.md", UUID.randomUUID());
    }
}
