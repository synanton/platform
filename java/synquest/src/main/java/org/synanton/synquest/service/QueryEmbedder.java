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
                         EmbeddingShape shape) {
        this.llmClient = llmClient;
        this.model = props.embedding().model();
        this.required = required;
        this.shape = shape;
    }

    public QueryEmbedder(LlmClient llmClient, org.synanton.synquest.config.SynquestProperties props, boolean required) {
        this(llmClient, props, required, new EmbeddingShape(props));
    }

    public float[] embed(String query) {
        return embed(query, null);
    }

    /** Tenant-scoped embedding: the GPU plane authorizes tenant_id; HTTP clients ignore it. */
    public float[] embed(String query, String tenant) {
        var response = TenantAwareLlmClient.embed(llmClient, new EmbedRequest(model, List.of(query)), tenant);
        if (response == null || response.embeddings() == null || response.embeddings().isEmpty()) {
            throw new IllegalStateException("query embedding returned no vector");
        }
        // Same truncation + normalisation as the index build (EmbeddingShape); a vector that
        // can't be made index-sized throws, so it's never silently dropped from the dense side.
        return shape.fit(response.embeddings().get(0));
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
