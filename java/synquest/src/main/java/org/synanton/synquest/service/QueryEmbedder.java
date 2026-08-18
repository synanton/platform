package org.synanton.synquest.service;

import org.synanton.llm.EmbedRequest;
import org.synanton.llm.LlmClient;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QueryEmbedder {

    private final LlmClient llmClient;
    private final String model;
    private final boolean normaliseL2;

    public QueryEmbedder(LlmClient llmClient,
                         org.synanton.synquest.config.SynquestProperties props) {
        this.llmClient = llmClient;
        this.model = props.embedding().model();
        this.normaliseL2 = props.embedding().normaliseL2();
    }

    public float[] embed(String query) {
        var response = llmClient.embed(new EmbedRequest(model, List.of(query)));
        float[] vec = response.embeddings().get(0);
        return normaliseL2 ? normalise(vec) : vec;
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
