package org.synanton.synquest.service;

import org.springframework.beans.factory.annotation.Value;
import org.synanton.llm.TenantAwareLlmClient;

import org.synanton.llm.EmbedRequest;
import org.synanton.llm.LlmClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QueryEmbedder {

    private final LlmClient llmClient;
    private final String model;
    private final boolean required;
    private final EmbeddingShape shape;
    private final QueryEmbeddingCache cache;

    /**
     * @param required {@code synquest.embedding.required}: when true, a failed query embedding
     *                 fails the search (HTTP 503) instead of silently degrading to BM25-only.
     *                 Set under the gpu-plane profile, so a "dense" benchmark run can never be
     *                 secretly lexical (retrieval benchmark plan §6 Phase B1-G).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public QueryEmbedder(LlmClient llmClient,
                         org.synanton.synquest.config.SynquestProperties props,
                         @Value("${synquest.embedding.required:false}") boolean required,
                         EmbeddingShape shape,
                         QueryEmbeddingCache cache) {
        this.llmClient = llmClient;
        this.model = props.embedding().model();
        this.required = required;
        this.shape = shape;
        this.cache = cache;
    }

    public QueryEmbedder(LlmClient llmClient, org.synanton.synquest.config.SynquestProperties props, boolean required) {
        this(llmClient, props, required, new EmbeddingShape(props), new QueryEmbeddingCache(0));
    }

    /** A query vector, and whether it came from {@link QueryEmbeddingCache} (no GPU-plane request). */
    public record QueryVector(float[] vector, boolean cached) {}

    public float[] embed(String query) {
        return embed(query, null);
    }

    /** Tenant-scoped embedding: the GPU plane authorizes tenant_id; HTTP clients ignore it. */
    public float[] embed(String query, String tenant) {
        return embedForSearch(query, tenant).vector();
    }

    public QueryVector embedForSearch(String query, String tenant) {
        var hit = cache.get(tenant, model, query);
        if (hit.isPresent()) {
            return new QueryVector(hit.get(), true);
        }
        var response = TenantAwareLlmClient.embed(llmClient, new EmbedRequest(model, List.of(query)), tenant);
        if (response == null || response.embeddings() == null || response.embeddings().isEmpty()) {
            throw new IllegalStateException("query embedding returned no vector");
        }
        // Same truncation + normalisation as the index build (EmbeddingShape); a vector that
        // can't be made index-sized throws, so it's never silently dropped from the dense side.
        float[] v = shape.fit(response.embeddings().get(0));
        cache.put(tenant, model, query, v);
        return new QueryVector(v, false);
    }

    public boolean required() {
        return required;
    }

    static float[] normalise(float[] vec) {
        double norm = 0.0;
        for (float v : vec) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm < 1e-12) return vec;
        float[] out = new float[vec.length];
        for (int i = 0; i < vec.length; i++) out[i] = (float) (vec[i] / norm);
        return out;
    }
}
