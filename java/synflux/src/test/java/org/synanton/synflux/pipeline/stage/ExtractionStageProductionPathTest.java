package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.client.StructuredExtractionRequiredException;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.ExtractionResult;
import org.synanton.extraction.v1.ExtractionStatus;
import org.synanton.extraction.v1.PriorityClass;
import org.synanton.extraction.v1.StructuredPayload;
import org.synanton.synflux.document.DocumentStructureService;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ObjectStorePort;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

class ExtractionStageProductionPathTest {

    @Test
    void neverConstructsPlatformStructureServiceOnSuccessOrFailure() {
        try (MockedConstruction<DocumentStructureService> constructions =
                     mockConstruction(DocumentStructureService.class)) {
            ExtractionPlaneClient success = client(true);
            DocumentPayload payload = DocumentPayload.newBuilder()
                .setFlattenedText("payload text")
                .build();
            when(success.extract(any())).thenReturn(ExtractionResult.newBuilder()
                .setStatus(ExtractionStatus.STATUS_COMPLETED)
                .setFlattenedText("payload text")
                .setPayload(StructuredPayload.newBuilder().setInlineContent(payload.toByteString()))
                .build());
            ParsedDocument parsed = new ExtractionStage(success, mock(ObjectStorePort.class), "hot")
                .apply(doc(), new StageContext("demo", "job", null));

            assertThat(parsed.text()).isEqualTo("payload text");
            assertThat(parsed.structure()).isNull();

            ExtractionPlaneClient failure = client(true);
            when(failure.extract(any())).thenThrow(new IllegalStateException("unavailable"));
            assertThatThrownBy(() -> new ExtractionStage(failure, mock(ObjectStorePort.class), "hot")
                .apply(doc(), new StageContext("demo", "job", null)))
                .isInstanceOf(StructuredExtractionRequiredException.class);

            assertThat(constructions.constructed()).isEmpty();
        }
    }

    private static ExtractionPlaneClient client(boolean enabled) {
        ExtractionPlaneClient client = mock(ExtractionPlaneClient.class);
        when(client.isEnabled()).thenReturn(enabled);
        when(client.defaultPriorityClass()).thenReturn(PriorityClass.PRIORITY_NORMAL);
        return client;
    }

    private static AcquiredDocument doc() {
        ContentRef ref = new ContentRef("file", "file:///test.md", "text/markdown", 10, Instant.now());
        return new AcquiredDocument(ref, "# Title\n".getBytes(), "sha", "text/markdown",
            "file:///test.md", UUID.randomUUID());
    }
}
