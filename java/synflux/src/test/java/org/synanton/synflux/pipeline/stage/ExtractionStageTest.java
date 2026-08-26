package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.Test;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ObjectStorePort;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExtractionStageTest {

    @Test
    void shouldFallBackToTikaWhenPlaneIsNotConfigured() {
        ObjectStorePort store = mock(ObjectStorePort.class);
        ExtractionStage stage = new ExtractionStage(null, store, "synanton-hot");
        ContentRef ref = new ContentRef("file", "file:///tmp/a.txt", "text/plain", 5, Instant.now());
        AcquiredDocument doc = new AcquiredDocument(
                ref, "hello tika".getBytes(), "aa", "text/plain", "file:///tmp/a.txt", UUID.randomUUID());
        ParsedDocument parsed = stage.apply(doc, new StageContext("demo", "job", null));
        assertThat(parsed.text()).contains("hello tika");
        assertThat(parsed.documentPayload()).isNull();
    }
}
