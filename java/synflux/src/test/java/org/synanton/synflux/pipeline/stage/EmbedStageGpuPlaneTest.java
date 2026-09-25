package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.TenantAwareLlmClient;
import org.synanton.synflux.domain.*;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** EmbedStage with a tenant-aware (GPU plane) client: tenant propagation and fail-closed mode. */
class EmbedStageGpuPlaneTest {

    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final List<String> tenants = new ArrayList<>();

    private TenantAwareLlmClient client(Function<EmbedRequest, EmbedResponse> f) {
        return new TenantAwareLlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r, String tenant) { tenants.add(tenant); return f.apply(r); }
            @Override public EmbedResponse embed(EmbedRequest r) { throw new AssertionError("tenant-less call"); }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
    }

    private static ChunkedDocument doc(int n) {
        var ref = new ContentRef("file", "file:///t.txt", "text/plain", 100, Instant.now());
        var acquired = new AcquiredDocument(ref, new byte[0], "sha", "text/plain", "file:///t.txt", UUID.randomUUID());
        var parsed = new ParsedDocument(acquired, "text", Map.of(), null);
        List<SemanticChunk> chunks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            chunks.add(new SemanticChunk("d-c" + i, "d", i, SemanticChunk.ChunkType.FALLBACK, "chunk " + i, null,
                    List.of(), null, List.of(), -1, -1, 10, false, Map.of(), SemanticChunk.PUBLIC_ONLY, "sha" + i));
        }
        return new ChunkedDocument(parsed, chunks);
    }

    private static List<float[]> vectors(int n) {
        List<float[]> v = new ArrayList<>();
        for (int i = 0; i < n; i++) v.add(new float[]{i, 1f});
        return v;
    }

    @Test
    void jobTenantAndLogicalModelReachTheGpuPlane() {
        when(cache.readEmbeddingByChunkHash(any(), any(), any())).thenReturn(Optional.empty());
        List<String> models = new ArrayList<>();
        var stage = new EmbedStage(client(r -> { models.add(r.model()); return new EmbedResponse(vectors(r.inputs().size())); }),
                cache, "synanton-free-embedding", 32, true);

        stage.apply(doc(3), new StageContext("rb-fixed-g", UUID.randomUUID().toString(), null));

        assertThat(tenants).containsExactly("rb-fixed-g");
        assertThat(models).containsExactly("synanton-free-embedding");
        verify(cache, times(3)).upsertEmbedding(any(EmbeddingRow.class));
    }

    @Test
    void failOnErrorFailsTheDocumentInsteadOfPersistingItWithoutVectors() {
        when(cache.readEmbeddingByChunkHash(any(), any(), any())).thenReturn(Optional.empty());
        var stage = new EmbedStage(client(r -> { throw new IllegalStateException("circuit_open"); }),
                cache, "synanton-free-embedding", 32, true);

        assertThatThrownBy(() -> stage.apply(doc(2), new StageContext("rb-fixed-g", UUID.randomUUID().toString(), null)))
                .hasMessageContaining("circuit_open");
        verify(cache, never()).upsertEmbedding(any());
    }

    @Test
    void wrongVectorCountIsAFailureInFailClosedMode() {
        when(cache.readEmbeddingByChunkHash(any(), any(), any())).thenReturn(Optional.empty());
        var stage = new EmbedStage(client(r -> new EmbedResponse(vectors(1))), cache, "synanton-free-embedding", 32, true);

        assertThatThrownBy(() -> stage.apply(doc(2), new StageContext("rb-fixed-g", UUID.randomUUID().toString(), null)))
                .hasMessageContaining("1 vectors for 2 chunks");
        verify(cache, never()).upsertEmbedding(any());
    }
}
