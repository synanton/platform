package org.synanton.synquest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.synanton.synquest.api.dto.SearchResponse;
import org.synanton.synquest.config.SynquestProperties;

/**
 * SYN-VECTOR-001 B4: sequential metadata-first mode against the parallel default.
 * Two documents: refA matches lexically, refB is vector-identical but lexically
 * disjoint — so the sequential candidate universe visibly constrains the dense leg.
 */
class SearchServiceSequentialTest {

    @TempDir Path indexRoot;
    private final String tenant = "t";
    private final UUID refA = UUID.randomUUID();
    private final UUID refB = UUID.randomUUID();
    private SynquestProperties props;
    private LuceneIndexBuilder builder;
    private QueryEmbedder embedder;

    private static ChunkRow row(String tenant, UUID ref, int ordinal, String text) {
        return new ChunkRow(tenant, ref, ordinal, text, "s" + ordinal);
    }

    @BeforeEach
    void index() throws Exception {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        when(cache.listManifest(eq(tenant), anyInt())).thenReturn(List.of(
                new ManifestRow(tenant, refA, Instant.now(), 1,
                        "flat", 1, "EMBEDDED", "hot", null, "file:///a.pdf", "sha", 10, "application/pdf", null, null, null),
                new ManifestRow(tenant, refB, Instant.now(), 1,
                        "flat", 1, "EMBEDDED", "hot", null, "file:///b.pdf", "sha", 10, "application/pdf", null, null, null)));
        List<ChunkRow> rowsA = List.of(
                row(tenant, refA, 0, "switch manual header header header"),
                row(tenant, refA, 1, "switch default address is 192.168.10.12"));
        List<ChunkRow> rowsB = List.of(row(tenant, refB, 0, "zephyr quantum north"));
        when(cache.readChunks(tenant, refA)).thenReturn(new ArrayList<>(rowsA));
        when(cache.readChunks(tenant, refB)).thenReturn(new ArrayList<>(rowsB));
        when(cache.readEmbedding(tenant, refA, 0, "m"))
                .thenReturn(Optional.of(new EmbeddingRow(tenant, refA, 0, "m", "s0", new float[] {1f, 0f}, 2, Instant.now())));
        when(cache.readEmbedding(tenant, refA, 1, "m"))
                .thenReturn(Optional.of(new EmbeddingRow(tenant, refA, 1, "m", "s1", new float[] {1f, 1f}, 2, Instant.now())));
        when(cache.readEmbedding(tenant, refB, 0, "m"))
                .thenReturn(Optional.of(new EmbeddingRow(tenant, refB, 0, "m", "s0", new float[] {1f, 0f}, 2, Instant.now())));
        props = new SynquestProperties(new SynquestProperties.Index(indexRoot.toString(), true, 30), null,
                new SynquestProperties.Embedding("m", 2, true, 0), null);
        var shape = new EmbeddingShape(props);
        builder = new LuceneIndexBuilder(cache, props, shape, true);
        LlmClient emb = new LlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r) { return new EmbedResponse(List.of(new float[]{1f, 0f})); }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
        embedder = new QueryEmbedder(emb, props, true, shape, new QueryEmbeddingCache(0));
    }

    private SearchService service() {
        SearchService service = new SearchService(builder, embedder, props);
        service.initTenant(tenant);
        return service;
    }

    private SearchRequest request(String query, String executionMode) {
        return new SearchRequest(tenant, query, 10, 10, 10, 60, null, null, null, null, executionMode);
    }

    @Test
    void shouldConstrainDenseLegToLexicalUniverse() throws Exception {
        SearchService service = service();

        SearchResponse sequential = service.search(request("switch", "sequential"));
        SearchResponse parallel = service.search(request("switch", null));

        assertThat(sequential.hits()).isNotEmpty();
        assertThat(sequential.hits())
                .allSatisfy(hit -> assertThat(hit.contentRefId()).isEqualTo(refA));
        assertThat(parallel.hits())
                .anySatisfy(hit -> assertThat(hit.contentRefId()).isEqualTo(refB));
    }

    @Test
    void shouldProduceIdenticalShapesOnBroadQuery() throws Exception {
        SearchService service = service();

        SearchResponse sequential = service.search(request("switch zephyr manual quantum north header address", "sequential"));
        SearchResponse parallel = service.search(request("switch zephyr manual quantum north header address", null));

        assertThat(sequential.hits()).isEqualTo(parallel.hits());
        assertThat(sequential.trace()).isNotNull();
        assertThat(parallel.trace()).isNotNull();
    }

    @Test
    void shouldHonorDeploymentDefaultSequential() throws Exception {
        props = new SynquestProperties(
                new SynquestProperties.Index(indexRoot.toString(), true, 30), null,
                new SynquestProperties.Embedding("m", 2, true, 0),
                new SynquestProperties.Execution("sequential"));
        SearchService service = service();

        SearchResponse response = service.search(request("switch", null));

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits())
                .allSatisfy(hit -> assertThat(hit.contentRefId()).isEqualTo(refA));
    }

    @Test
    void shouldRejectUnknownExecutionMode() {
        SearchService service = service();

        assertThatThrownBy(() -> service.search(request("switch", "sideways")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sideways");
    }
}
