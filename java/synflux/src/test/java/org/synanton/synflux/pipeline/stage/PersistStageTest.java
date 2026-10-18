package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.KnowledgeProcessingRunRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistStageTest {

    private static SynfluxProperties props() {
        return new SynfluxProperties(
                new SynfluxProperties.Ingest(1, 1000, new SynfluxProperties.Ingest.Chunk("semantic-v1", 400, 50)),
                new SynfluxProperties.Pipeline(false, false, false),
                new SynfluxProperties.Enrichment("http://x", "m", 1),
                new SynfluxProperties.Embedding("http://x", "m", 1),
                new SynfluxProperties.Annotation("http://x", "rule-engine", "1.0"),
                new SynfluxProperties.Kafka(1, 1, 1)
        );
    }

    private static ChunkedDocument doc(UUID refId) {
        ContentRef contentRef = new ContentRef("file", "file:///tmp/a.md", "text/markdown", 10, Instant.now());
        AcquiredDocument acquired = new AcquiredDocument(contentRef, new byte[]{1}, "aa", "text/markdown",
                "file:///tmp/a.md", refId, "v1:tenant_id=demo&artifact_id=a1&digest=sha256%3Aaa");
        ParsedDocument parsed = new ParsedDocument(acquired, "hello", Map.of(), null);
        SemanticChunk chunk = new SemanticChunk(
                "c0", refId.toString(), 0, SemanticChunk.ChunkType.SECTION, "hello", null,
                List.of("Supply chain", "Europe"), "Europe", List.of("e1"), 2, 2, 1, false, Map.of(),
                SemanticChunk.PUBLIC_ONLY, "bb");
        return new ChunkedDocument(parsed, List.of(chunk));
    }

    @Test
    void persistsChunksWithProvenanceAndCompletesProcessingRun() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);

        UUID refId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID versionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        StageContext context = new StageContext("demo", "job", props()).withSourceVersionId(versionId);

        stage.apply(doc(refId), context);

        ArgumentCaptor<List<ChunkRow>> chunks = ArgumentCaptor.forClass(List.class);
        verify(cache).insertChunks(chunks.capture());
        assertThat(chunks.getValue()).singleElement().satisfies(row -> {
            assertThat(row.sourceVersionIds()).containsExactly(versionId);
            assertThat(row.processingRunId()).isNotNull();
            assertThat(row.sourceElementsJson()).contains("e1");
        });

        ArgumentCaptor<KnowledgeProcessingRunRow> runs = ArgumentCaptor.forClass(KnowledgeProcessingRunRow.class);
        verify(cache, org.mockito.Mockito.times(2)).upsertKnowledgeProcessingRun(runs.capture());
        UUID runId = runs.getAllValues().getFirst().processingRunId();
        assertThat(runs.getAllValues().getFirst().status()).isEqualTo("RUNNING");
        assertThat(runs.getAllValues().getLast().status()).isEqualTo("COMPLETED");
        assertThat(runs.getAllValues().getLast().processingRunId()).isEqualTo(runId);
        assertThat(runs.getAllValues().getFirst().producer()).isEqualTo("synflux-knowledge");
        assertThat(runs.getAllValues().getFirst().producerVersion()).isEqualTo("semantic-v1:1");
        assertThat(runs.getAllValues().getFirst().inputJson()).contains(refId.toString());
        assertThat(runs.getAllValues().getFirst().inputSourceVersionIds()).containsExactly(versionId);
        assertThat(runs.getAllValues().getLast().outputJson()).contains("chunk_count");

        ArgumentCaptor<ManifestRow> manifest = ArgumentCaptor.forClass(ManifestRow.class);
        verify(cache).upsertManifest(manifest.capture());
        assertThat(manifest.getValue().processingRunId()).isEqualTo(runId);
    }

    @Test
    void reprocessingSameInputsIsIdempotent() {
        IngestionCacheClient first = mock(IngestionCacheClient.class);
        IngestionCacheClient second = mock(IngestionCacheClient.class);
        UUID refId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID versionId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        new PersistStage(first).apply(doc(refId), new StageContext("demo", "job", props()).withSourceVersionId(versionId));
        new PersistStage(second).apply(doc(refId), new StageContext("demo", "job", props()).withSourceVersionId(versionId));

        UUID firstRunId = capturedRunId(first);
        UUID secondRunId = capturedRunId(second);
        assertThat(secondRunId).isEqualTo(firstRunId);
    }

    @Test
    void missingSourceVersionIdFailsClosed() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);

        assertThatThrownBy(() -> stage.apply(doc(UUID.randomUUID()), new StageContext("demo", "job", props())))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("source version");
        verify(cache, never()).insertChunks(anyList());
        verify(cache, never()).upsertManifest(any());
    }

    @Test
    void cacheWriteFailureRecordsFailedRunAndDoesNotPublishManifest() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);
        UUID refId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalStateException("cassandra down"))
            .when(cache).insertChunks(anyList());

        assertThatThrownBy(() -> stage.apply(doc(refId),
            new StageContext("demo", "job", props()).withSourceVersionId(versionId)))
            .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<KnowledgeProcessingRunRow> runs = ArgumentCaptor.forClass(KnowledgeProcessingRunRow.class);
        verify(cache, org.mockito.Mockito.times(2)).upsertKnowledgeProcessingRun(runs.capture());
        assertThat(runs.getAllValues().getLast().status()).isEqualTo("FAILED");
        assertThat(runs.getAllValues().getLast().errorCode()).isEqualTo("IllegalStateException");
        assertThat(runs.getAllValues().getLast().errorMessage()).contains("cassandra down");
        verify(cache, never()).upsertManifest(any());
    }

    @Test
    void priorRunAccumulatesObservingSourceVersions() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);
        UUID refId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID firstVersion = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID secondVersion = UUID.fromString("33333333-3333-3333-3333-333333333333");
        StageContext context = new StageContext("demo", "job", props()).withSourceVersionId(secondVersion);
        // Prior chunk rows attribute the artifact to the first observing version.
        ContentRef contentRef = new ContentRef("file", "file:///tmp/a.md", "text/markdown", 10, Instant.now());
        ChunkRow priorChunk = new ChunkRow("demo", refId, 0, "hello", "bb",
            2, 2, "Section", "SECTION", "Section", "[\"e1\"]", 3, "", false,
            SemanticChunk.PUBLIC_ONLY, "", "", 0,
            java.util.Set.of(firstVersion), UUID.randomUUID());
        when(cache.readChunks("demo", refId)).thenReturn(List.of(priorChunk));

        stage.apply(doc(refId), context);

        ArgumentCaptor<KnowledgeProcessingRunRow> runs = ArgumentCaptor.forClass(KnowledgeProcessingRunRow.class);
        verify(cache, org.mockito.Mockito.times(2)).upsertKnowledgeProcessingRun(runs.capture());
        assertThat(runs.getAllValues().getFirst().inputSourceVersionIds())
            .containsExactlyInAnyOrder(firstVersion, secondVersion);
        ArgumentCaptor<List<ChunkRow>> chunks = ArgumentCaptor.forClass(List.class);
        verify(cache).insertChunks(chunks.capture());
        for (ChunkRow row : chunks.getValue()) {
            assertThat(row.sourceVersionIds()).containsExactlyInAnyOrder(firstVersion, secondVersion);
        }
    }

    private static UUID capturedRunId(IngestionCacheClient cache) {
        ArgumentCaptor<KnowledgeProcessingRunRow> runs = ArgumentCaptor.forClass(KnowledgeProcessingRunRow.class);
        verify(cache, org.mockito.Mockito.times(2)).upsertKnowledgeProcessingRun(runs.capture());
        return runs.getAllValues().getFirst().processingRunId();
    }
}
