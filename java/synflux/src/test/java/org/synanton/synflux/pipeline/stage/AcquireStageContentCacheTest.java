package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.Test;
import org.synanton.contentcache.ContentIntegrityException;
import org.synanton.contentcache.ContentNotFoundException;
import org.synanton.contentcache.ContentReference;
import org.synanton.contentcache.ContentStore;
import org.synanton.contentcache.InMemoryContentStore;
import org.synanton.contentcache.PutResult;
import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;
import org.synanton.synvault.port.ContentPullPort;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AcquireStageContentCacheTest {

    @Test
    void corruptedContentCacheReadFailsBeforeExtraction() {
        ContentPullPort pullPort = mock(ContentPullPort.class);
        ContentStore contentStore = mock(ContentStore.class);
        byte[] sourceBytes = "trusted source bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentRef ref = new ContentRef(
            "file", "file:///fixture/document.md", "text/markdown", sourceBytes.length,
            Instant.parse("2026-10-17T11:00:00Z"));
        ContentReference reference = new ContentReference(
            "tenant-a", UUID.randomUUID().toString(), InMemoryContentStore.sha256(sourceBytes), 1, 1);
        when(pullPort.open("tenant-a", ref)).thenReturn(new ByteArrayInputStream(sourceBytes));
        when(contentStore.put(eq("tenant-a"), any(byte[].class), any()))
            .thenReturn(new PutResult(reference, false));
        when(contentStore.get(reference)).thenReturn("corrupted bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AcquireStage stage = new AcquireStage(pullPort, contentStore);

        assertThatThrownBy(() -> stage.apply(ref, new StageContext("tenant-a", "run-1", testProperties())))
            .isInstanceOf(ContentIntegrityException.class)
            .hasMessageContaining("round-trip");
        verify(contentStore).get(reference);
    }

    @Test
    void missingContentCacheArtifactFailsClosed() {
        ContentPullPort pullPort = mock(ContentPullPort.class);
        ContentStore contentStore = mock(ContentStore.class);
        byte[] sourceBytes = "trusted source bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ContentRef ref = new ContentRef(
            "file", "file:///fixture/document.md", "text/markdown", sourceBytes.length,
            Instant.parse("2026-10-17T11:00:00Z"));
        ContentReference reference = new ContentReference(
            "tenant-a", UUID.randomUUID().toString(), InMemoryContentStore.sha256(sourceBytes), 1, 1);
        when(pullPort.open("tenant-a", ref)).thenReturn(new ByteArrayInputStream(sourceBytes));
        when(contentStore.put(eq("tenant-a"), any(byte[].class), any()))
            .thenReturn(new PutResult(reference, false));
        when(contentStore.get(reference)).thenThrow(new ContentNotFoundException("artifact not found"));

        AcquireStage stage = new AcquireStage(pullPort, contentStore);

        assertThatThrownBy(() -> stage.apply(ref, new StageContext("tenant-a", "run-1", testProperties())))
            .isInstanceOf(ContentNotFoundException.class);
        verify(contentStore).get(reference);
    }

    private static SynfluxProperties testProperties() {
        return new SynfluxProperties(
            new SynfluxProperties.Ingest(1, 1000,
                new SynfluxProperties.Ingest.Chunk("semantic-v1", 400, 50)),
            new SynfluxProperties.Pipeline(false, false, false),
            new SynfluxProperties.Enrichment("http://x", "m", 1),
            new SynfluxProperties.Embedding("http://x", "m", 1),
            new SynfluxProperties.Annotation("http://x", "rule-engine", "1.0"),
            new SynfluxProperties.Kafka(1, 1, 1)
        );
    }
}
