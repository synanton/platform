package org.synanton.synflux.pipeline.stage;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.client.ExtractionClientMetrics;
import org.synanton.extraction.client.ExtractionClientProperties;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.client.StructuredExtractionRequiredException;
import org.synanton.extraction.v1.ExtractionResult;
import org.synanton.extraction.v1.ExtractionStatus;
import org.synanton.extraction.v1.PriorityClass;
import org.synanton.extraction.v1.StructuredPayload;
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
import static org.mockito.Mockito.when;

class ExtractionStageTest {

    @Test
    void shouldFailWhenStructuredRequiredAndResultIsUnsuccessful() {
        ObjectStorePort store = mock(ObjectStorePort.class);
        ExtractionPlaneClient client = mock(ExtractionPlaneClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.defaultPriorityClass()).thenReturn(PriorityClass.PRIORITY_NORMAL);
        when(client.extract(any())).thenReturn(ExtractionResult.newBuilder()
                .setStatus(ExtractionStatus.STATUS_FAILED)
                .build());
        ExtractionStage stage = new ExtractionStage(client, store, "synanton-hot");

        assertThatThrownBy(() -> stage.apply(doc(), new StageContext("demo", "job", null)))
                .isInstanceOf(StructuredExtractionRequiredException.class);
    }

    @Test
    void shouldFailWhenStructuredRequiredAndPayloadIsMalformed() {
        ObjectStorePort store = mock(ObjectStorePort.class);
        ExtractionPlaneClient client = mock(ExtractionPlaneClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.defaultPriorityClass()).thenReturn(PriorityClass.PRIORITY_NORMAL);
        when(client.extract(any())).thenReturn(ExtractionResult.newBuilder()
                .setStatus(ExtractionStatus.STATUS_COMPLETED)
                .setPayload(StructuredPayload.newBuilder()
                    .setInlineContent(ByteString.copyFrom(new byte[]{0, 1, 2, 3})))
                .build());
        ExtractionStage stage = new ExtractionStage(client, store, "synanton-hot");

        assertThatThrownBy(() -> stage.apply(doc(), new StageContext("demo", "job", null)))
                .isInstanceOf(StructuredExtractionRequiredException.class);
    }

    private static AcquiredDocument doc() {
        ContentRef ref = new ContentRef("file", "file:///tmp/a.txt", "text/plain", 5, Instant.now());
        return new AcquiredDocument(ref, "hello".getBytes(), "aa", "text/plain",
                "file:///tmp/a.txt", UUID.randomUUID());
    }

    @Test
    void shouldFailWhenStructuredRequiredAndClientDisabled() {
        ObjectStorePort store = mock(ObjectStorePort.class);
        ExtractionClientProperties props = new ExtractionClientProperties(false, "localhost:9091", "sync", 120, 5, "NORMAL");
        ExtractionPlaneClient client = new ExtractionPlaneClient(props, new ExtractionClientMetrics(null));
        ExtractionStage stage = new ExtractionStage(client, store, "synanton-hot");

        ContentRef ref = new ContentRef("file", "file:///tmp/a.txt", "text/plain", 5, Instant.now());
        AcquiredDocument doc = new AcquiredDocument(
                ref, "hello".getBytes(), "aa", "text/plain", "file:///tmp/a.txt", UUID.randomUUID());

        assertThatThrownBy(() -> stage.apply(doc, new StageContext("demo", "job", null)))
                .isInstanceOf(StructuredExtractionRequiredException.class);
    }
}
