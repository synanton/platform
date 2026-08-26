package org.synanton.synflux.pipeline.stage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.client.ExtractionClientMetrics;
import org.synanton.extraction.client.ExtractionClientProperties;
import org.synanton.extraction.client.ExtractionFallbackPolicy;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.client.LocalTikaFallbackExtractor;
import org.synanton.extraction.client.StructuredExtractionRequiredException;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ObjectStorePort;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ExtractionStageTest {

    @Test
    void shouldFallBackToTikaWhenPlaneIsNotConfigured() {
        ObjectStorePort store = mock(ObjectStorePort.class);
        ExtractionClientProperties props = new ExtractionClientProperties(
                false, "localhost:9091", "sync", 120, 5, "local-tika", "NORMAL");
        ExtractionPlaneClient client = new ExtractionPlaneClient(props, new ExtractionClientMetrics(null));
        ExtractionStage stage = new ExtractionStage(
                client,
                new LocalTikaFallbackExtractor(),
                ExtractionFallbackPolicy.FALLBACK_LOCAL_TIKA,
                new ExtractionClientMetrics(new SimpleMeterRegistry()),
                store,
                "synanton-hot");

        ContentRef ref = new ContentRef("file", "file:///tmp/a.txt", "text/plain", 5, Instant.now());
        AcquiredDocument doc = new AcquiredDocument(
                ref, "hello tika".getBytes(), "aa", "text/plain", "file:///tmp/a.txt", UUID.randomUUID());
        ParsedDocument parsed = stage.apply(doc, new StageContext("demo", "job", null));

        assertThat(parsed.text()).contains("hello tika");
        assertThat(parsed.documentPayload()).isNull();
    }

    @Test
    void shouldFailWhenStructuredRequiredAndClientDisabled() {
        ObjectStorePort store = mock(ObjectStorePort.class);
        ExtractionClientProperties props = new ExtractionClientProperties(
                false, "localhost:9091", "sync", 120, 5, "fail", "NORMAL");
        ExtractionPlaneClient client = new ExtractionPlaneClient(props, new ExtractionClientMetrics(null));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExtractionStage stage = new ExtractionStage(
                client,
                new LocalTikaFallbackExtractor(),
                ExtractionFallbackPolicy.STRUCTURED_REQUIRED,
                new ExtractionClientMetrics(registry),
                store,
                "synanton-hot");

        ContentRef ref = new ContentRef("file", "file:///tmp/a.txt", "text/plain", 5, Instant.now());
        AcquiredDocument doc = new AcquiredDocument(
                ref, "hello".getBytes(), "aa", "text/plain", "file:///tmp/a.txt", UUID.randomUUID());

        assertThatThrownBy(() -> stage.apply(doc, new StageContext("demo", "job", null)))
                .isInstanceOf(StructuredExtractionRequiredException.class);
        assertThat(registry.get("extraction_client_fallback_total")
                .tag("reason", "client_disabled")
                .counter()
                .count()).isEqualTo(1.0);
    }
}
