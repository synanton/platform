package org.synanton.synquest.service;

import org.junit.jupiter.api.Test;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.CompletionResponse;
import org.synanton.llm.EmbedRequest;
import org.synanton.llm.EmbedResponse;
import org.synanton.llm.LlmClient;
import org.synanton.llm.TenantAwareLlmClient;
import org.synanton.synquest.config.SynquestProperties;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tenant propagation to tenant-aware (GPU plane) clients, and the no-vector guard. */
class QueryEmbedderTenantTest {

    private static final SynquestProperties PROPS = new SynquestProperties(null, null,
            new SynquestProperties.Embedding("synanton-free-embedding", 2, true, 0));

    @Test
    void tenantReachesTenantAwareClientWithTheLogicalModel() {
        List<String> seen = new ArrayList<>();
        TenantAwareLlmClient client = new TenantAwareLlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r, String tenant) {
                seen.add(tenant + "|" + r.model());
                return new EmbedResponse(List.of(new float[]{3f, 4f}));
            }
            @Override public EmbedResponse embed(EmbedRequest r) { throw new AssertionError("tenant-less call"); }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
        float[] v = new QueryEmbedder(client, PROPS, true).embed("q", "rb-fixed-g");

        assertThat(seen).containsExactly("rb-fixed-g|synanton-free-embedding");
        assertThat(v[0]).isEqualTo(0.6f);
    }

    @Test
    void plainClientIsCalledWithoutTenantAsBefore() {
        LlmClient http = new LlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r) { return new EmbedResponse(List.of(new float[]{1f, 0f})); }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
        QueryEmbedder e = new QueryEmbedder(http, PROPS, false);
        assertThat(e.embed("q", "any")).containsExactly(1f, 0f);
        assertThat(e.required()).isFalse();
    }

    @Test
    void queryVectorsGetTheSameTruncationAsTheIndex() {
        LlmClient native2048 = new LlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r) {
                float[] v = new float[2048];
                v[0] = 3f; v[1] = 4f; v[2000] = 100f; // the tail beyond 1024 must be cut, not folded in
                return new EmbedResponse(List.of(v));
            }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
        var truncating = new SynquestProperties(null, null, new SynquestProperties.Embedding("m", 1024, true, 1024));
        float[] v = new QueryEmbedder(native2048, truncating, true).embed("q", "t");
        assertThat(v).hasSize(1024);
        assertThat(v[0]).isEqualTo(0.6f);

        var strict = new SynquestProperties(null, null, new SynquestProperties.Embedding("m", 768, true, 0));
        assertThatThrownBy(() -> new QueryEmbedder(native2048, strict, true).embed("q", "t"))
                .isInstanceOf(EmbeddingShape.DimensionMismatchException.class);
    }

    @Test
    void cacheServesRepeatedQueriesPerTenantWithoutAnotherRequest() {
        int[] calls = {0};
        TenantAwareLlmClient client = new TenantAwareLlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r, String tenant) { calls[0]++; return new EmbedResponse(List.of(new float[]{3f, 4f})); }
            @Override public EmbedResponse embed(EmbedRequest r) { throw new AssertionError(); }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
        var e = new QueryEmbedder(client, PROPS, true, new EmbeddingShape(PROPS), new QueryEmbeddingCache(10));

        assertThat(e.embedForSearch("q", "rb-fixed-g").cached()).isFalse();
        var again = e.embedForSearch("q", "rb-fixed-g");
        assertThat(again.cached()).isTrue();
        assertThat(again.vector()).containsExactly(0.6f, 0.8f);
        assertThat(calls[0]).isEqualTo(1);
        // another tenant is a cache miss: the GPU plane authorizes each tenant itself
        assertThat(e.embedForSearch("q", "rb-semantic-g").cached()).isFalse();
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    void cacheIsOffByDefaultAndEvictsLeastRecentlyUsed() {
        var off = new QueryEmbeddingCache(0);
        off.put("t", "m", "q", new float[]{1f});
        assertThat(off.get("t", "m", "q")).isEmpty();

        var lru = new QueryEmbeddingCache(2);
        lru.put("t", "m", "a", new float[]{1f});
        lru.put("t", "m", "b", new float[]{2f});
        lru.get("t", "m", "a");
        lru.put("t", "m", "c", new float[]{3f});
        assertThat(lru.get("t", "m", "b")).isEmpty();
        assertThat(lru.get("t", "m", "a")).isPresent();
        assertThat(lru.size()).isEqualTo(2);
    }

    @Test
    void failuresAreNotCached() {
        int[] calls = {0};
        LlmClient failing = new LlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r) { calls[0]++; throw new IllegalStateException("circuit_open"); }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
        var e = new QueryEmbedder(failing, PROPS, true, new EmbeddingShape(PROPS), new QueryEmbeddingCache(10));
        assertThatThrownBy(() -> e.embedForSearch("q", "t")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> e.embedForSearch("q", "t")).isInstanceOf(IllegalStateException.class);
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    void emptyResultIsAFailureNotAMissingVector() {
        LlmClient empty = new LlmClient() {
            @Override public EmbedResponse embed(EmbedRequest r) { return new EmbedResponse(List.of()); }
            @Override public CompletionResponse complete(CompletionRequest r) { return null; }
        };
        assertThatThrownBy(() -> new QueryEmbedder(empty, PROPS, true).embed("q", "t"))
                .isInstanceOf(IllegalStateException.class);
    }
}
