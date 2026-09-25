package org.synanton.synquest.service;

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
import org.synanton.synquest.config.SynquestProperties;
import org.synanton.synquest.config.SynquestRerankProperties;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B2/T10: post-RRF cross-encoder reranking on a real Lucene index, fail closed. */
class SearchServiceRerankTest {

    @TempDir Path indexRoot;
    private final String tenant = "t";
    private final UUID ref = UUID.randomUUID();
    private SynquestProperties props;
    private LuceneIndexBuilder builder;
    private QueryEmbedder embedder;

    private static final List<String> TEXTS = List.of(
            "switch manual header header header",           // #0 lexically closest to the query
            "switch default address is 192.168.10.12",      // #1 the actual answer
            "switch firmware upgrade via ftp");             // #2

    @BeforeEach
    void index() throws Exception {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        when(cache.listManifest(eq(tenant), anyInt())).thenReturn(List.of(new ManifestRow(tenant, ref, Instant.now(), 1,
                "flat", 1, "EMBEDDED", "hot", null, "file:///m.pdf", "sha", 10, "application/pdf", null, null, null)));
        List<ChunkRow> rows = new ArrayList<>();
        for (int i = 0; i < TEXTS.size(); i++) {
            rows.add(new ChunkRow(tenant, ref, i, TEXTS.get(i), "s" + i));
            when(cache.readEmbedding(tenant, ref, i, "m"))
                    .thenReturn(Optional.of(new EmbeddingRow(tenant, ref, i, "m", "s" + i, new float[]{1f, i}, 2, Instant.now())));
        }
        when(cache.readChunks(tenant, ref)).thenReturn(rows);
        props = new SynquestProperties(new SynquestProperties.Index(indexRoot.toString(), true, 30), null,
                new SynquestProperties.Embedding("m", 2, true, 0));
        var shape = new EmbeddingShape(props);
        builder = new LuceneIndexBuilder(cache, props, shape, true);
        LlmClient emb = new LlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r) { return new EmbedResponse(List.of(new float[]{1f, 0f})); }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
        embedder = new QueryEmbedder(emb, props, true, shape, new QueryEmbeddingCache(0));
    }

    private SearchService service(SearchReranker r) {
        SearchService s = new SearchService(builder, embedder, props, r, new SynquestRerankProperties());
        s.initTenant(tenant);
        return s;
    }

    /** Scores by whether the passage contains the answer; records what it was given. */
    static class Fake implements SearchReranker {
        final List<List<String>> calls = new ArrayList<>();
        @Override public double[] scores(String t, String q, List<String> passages) {
            calls.add(passages);
            return passages.stream().mapToDouble(p -> p.contains("192.168.10.12") ? 0.99 : 0.01).toArray();
        }
        @Override public String model() { return "fake"; }
    }

    @Test
    void rerankReordersWidensCandidatesAndReportsTrace() throws Exception {
        Fake fake = new Fake();
        var res = service(fake).search(new SearchRequest(tenant, "switch header", 1, 100, 100, 60, true, 3));

        assertThat(fake.calls).hasSize(1);
        assertThat(fake.calls.get(0)).hasSize(3);                      // widened beyond top_k=1
        assertThat(res.hits()).hasSize(1);
        assertThat(res.hits().get(0).chunkOrdinal()).isEqualTo(1);     // the answer, not the lexical best
        assertThat(res.hits().get(0).scoreRerank()).isEqualTo(0.99);
        assertThat(res.trace().rerankMs()).isNotNull();
        assertThat(res.trace().rerankCandidates()).isEqualTo(3);
    }

    @Test
    void withoutRerankNothingChanges() throws Exception {
        Fake fake = new Fake();
        var res = service(fake).search(new SearchRequest(tenant, "switch header", 3, 100, 100, 60));
        assertThat(fake.calls).isEmpty();
        assertThat(res.hits()).allMatch(h -> h.scoreRerank() == null);
        assertThat(res.trace().rerankMs()).isNull();
    }

    @Test
    void rerankFailsClosedWhenUnconfiguredOrFailing() {
        assertThatThrownBy(() -> service(null).search(new SearchRequest(tenant, "q", 1, 100, 100, 60, true, 3)))
                .isInstanceOf(RerankUnavailableException.class);
        SearchReranker failing = new SearchReranker() {
            @Override public double[] scores(String t, String q, List<String> p) { throw new IllegalStateException("circuit_open"); }
            @Override public String model() { return "x"; }
        };
        assertThatThrownBy(() -> service(failing).search(new SearchRequest(tenant, "switch", 1, 100, 100, 60, true, 3)))
                .isInstanceOf(RerankUnavailableException.class).hasMessageContaining("circuit_open");
    }
}
