package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.LlmClient;
import org.synanton.synflux.domain.*;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmbedStageTest {

    private LlmClient fakeEmbed;
    private IngestionCacheClient fakeCache;
    private EmbedStage stage;

    @BeforeEach
    void setUp() {
        fakeEmbed = mock(LlmClient.class);
        fakeCache = mock(IngestionCacheClient.class);
        stage = new EmbedStage(fakeEmbed, fakeCache, "bge-base", 32);
    }

    private ChunkedDocument makeDoc(int numChunks) {
        var ref = new ContentRef("file", "file:///test.txt", "text/plain", 100, Instant.now());
        var acquired = new AcquiredDocument(ref, new byte[0], "sha256test", "text/plain", "file:///test.txt", UUID.randomUUID());
        var parsed = new ParsedDocument(acquired, "text", Map.of(), null);
        List<SemanticChunk> chunks = new ArrayList<>();
        for (int i = 0; i < numChunks; i++) {
            chunks.add(new SemanticChunk(
                "doc-c" + i, "doc", i, SemanticChunk.ChunkType.FALLBACK,
                "chunk " + i, null, List.of(), null, List.of(),
                -1, -1, 10, false, Map.of(), SemanticChunk.PUBLIC_ONLY, "sha" + i));
        }
        return new ChunkedDocument(parsed, chunks);
    }

    @Test
    void embedsAllChunks() {
        int n = 3;
        var embeddings = List.of(new float[]{0.1f, 0.2f}, new float[]{0.3f, 0.4f}, new float[]{0.5f, 0.6f});
        when(fakeEmbed.embed(any(EmbedRequest.class))).thenReturn(new EmbedResponse(embeddings));
        when(fakeCache.readEmbeddingByChunkHash(any(), any(), any())).thenReturn(Optional.empty());

        var doc = makeDoc(n);
        var ctx = new StageContext("demo", UUID.randomUUID().toString(), null);
        var result = stage.apply(doc, ctx);

        assertThat(result).isSameAs(doc);
        verify(fakeCache, times(n)).upsertEmbedding(any(EmbeddingRow.class));
    }

    @Test
    void cacheHitSkipsEmbedCall() {
        float[] cached = {0.1f, 0.2f};
        when(fakeCache.readEmbeddingByChunkHash(any(), any(), any()))
            .thenReturn(Optional.of(new EmbeddingRow("demo", UUID.randomUUID(), 0, "bge-base", "sha0", cached, 2, Instant.now())));

        var doc = makeDoc(1);
        var ctx = new StageContext("demo", UUID.randomUUID().toString(), null);
        stage.apply(doc, ctx);

        verify(fakeEmbed, never()).embed(any());
        verify(fakeCache, never()).upsertEmbedding(any());
    }

    @Test
    void batchSizeRespected() {
        int n = 5;
        when(fakeCache.readEmbeddingByChunkHash(any(), any(), any())).thenReturn(Optional.empty());
        // Batch size 2 → ceil(5/2) = 3 calls
        var stageBatch2 = new EmbedStage(fakeEmbed, fakeCache, "bge-base", 2);
        List<float[]> twoEmbeds = List.of(new float[]{0.1f}, new float[]{0.2f});
        List<float[]> oneEmbed = List.of(new float[]{0.3f});
        when(fakeEmbed.embed(any(EmbedRequest.class)))
            .thenReturn(new EmbedResponse(twoEmbeds))
            .thenReturn(new EmbedResponse(twoEmbeds))
            .thenReturn(new EmbedResponse(oneEmbed));

        var doc = makeDoc(n);
        var ctx = new StageContext("demo", UUID.randomUUID().toString(), null);
        stageBatch2.apply(doc, ctx);

        verify(fakeEmbed, times(3)).embed(any());
    }

    @Test
    void embedFailureDoesNotAbortJob() {
        when(fakeCache.readEmbeddingByChunkHash(any(), any(), any())).thenReturn(Optional.empty());
        when(fakeEmbed.embed(any(EmbedRequest.class))).thenThrow(new RuntimeException("vLLM down"));

        var doc = makeDoc(2);
        var ctx = new StageContext("demo", UUID.randomUUID().toString(), null);
        // Should not throw
        var result = stage.apply(doc, ctx);
        assertThat(result).isSameAs(doc);
    }
}
