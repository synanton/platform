package org.synanton.synquest.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.LlmClient;
import org.synanton.synquest.api.dto.SearchRequest;
import org.synanton.synquest.config.SynquestProperties;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Under synquest.embedding.required (the gpu-plane profile), a BM25-only search (top_k_dense 0)
 * must neither embed the query nor fail. A dense search with a failing embedder must still fail
 * closed. Regression found in the retrieval benchmark (T-INT-3 T01-q25 run: 503 on every query).
 */
class SearchServiceRequiredModeTest {

    @TempDir Path indexRoot;

    @Test
    void bm25OnlySearchSkipsEmbeddingAndDenseButDenseSearchStillFailsClosed() throws Exception {
        String tenant = "t";
        UUID ref = UUID.randomUUID();
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        when(cache.listManifest(eq(tenant), anyInt())).thenReturn(List.of(new ManifestRow(tenant, ref, Instant.now(), 1,
                "flat", 1, "EMBEDDED", "hot", null, "file:///a.md", "sha", 10, "text/markdown", null, null, null)));
        when(cache.readChunks(tenant, ref)).thenReturn(List.of(new ChunkRow(tenant, ref, 0, "acme revenue fiscal 2024", "s0")));
        when(cache.readEmbedding(tenant, ref, 0, "m"))
                .thenReturn(Optional.of(new EmbeddingRow(tenant, ref, 0, "m", "s0", new float[]{1f, 0f}, 2, Instant.now())));

        var props = new SynquestProperties(new SynquestProperties.Index(indexRoot.toString(), true, 30), null,
                new SynquestProperties.Embedding("m", 2, true, 0));
        AtomicInteger embedCalls = new AtomicInteger();
        LlmClient failing = new LlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r) { embedCalls.incrementAndGet(); throw new IllegalStateException("circuit_open"); }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
        var shape = new EmbeddingShape(props);
        var service = new SearchService(new LuceneIndexBuilder(cache, props, shape, true),
                new QueryEmbedder(failing, props, true, shape, new QueryEmbeddingCache(0)), props);
        service.initTenant(tenant);

        var bm25 = service.search(new SearchRequest(tenant, "acme revenue", 10, 0, 100, 60));
        assertThat(bm25.hits()).hasSize(1);
        assertThat(embedCalls.get()).isZero();

        assertThatThrownBy(() -> service.search(new SearchRequest(tenant, "acme revenue", 10, 100, 100, 60)))
                .isInstanceOf(EmbeddingUnavailableException.class);
        assertThat(embedCalls.get()).isEqualTo(1);
    }
}
