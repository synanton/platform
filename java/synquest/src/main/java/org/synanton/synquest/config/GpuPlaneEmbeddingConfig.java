package org.synanton.synquest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.synanton.gpu.client.GpuPlaneClientProperties;
import org.synanton.gpu.client.GpuPlaneEmbedClient;
import org.synanton.llm.LlmClient;

/**
 * Opt-in {@code gpu-plane} profile. Query embeddings go to the GPU plane over gRPC
 * {@code synanton.gpu.v1} with mTLS, and the search's tenant becomes the request's
 * {@code tenant_id}. This replaces the HTTP {@code /v1/embeddings} client. The client fails
 * closed; with {@code synquest.embedding.required=true} (set in application-gpu-plane.yml) a
 * failed embed fails the search. See the retrieval benchmark plan, §6 Phase B1-G.
 */
@Configuration
@Profile("gpu-plane")
public class GpuPlaneEmbeddingConfig {

    @Bean
    @ConfigurationProperties("gpu-plane")
    public GpuPlaneClientProperties gpuPlaneClientProperties() {
        return new GpuPlaneClientProperties();
    }

    @Bean(destroyMethod = "close")
    public LlmClient llmClient(GpuPlaneClientProperties props, ObjectMapper objectMapper) {
        return new GpuPlaneEmbedClient(props, objectMapper);
    }

    /** Cross-encoder reranking via the GPU plane (RERANK op), fail closed. Retrieval benchmark B2, T10. */
    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "synquest.rerank.enabled", havingValue = "true")
    public org.synanton.gpu.client.GpuPlaneRerankClient gpuPlaneRerankClient(GpuPlaneClientProperties props,
                                                                              ObjectMapper objectMapper,
                                                                              SynquestRerankProperties rerank) {
        return new org.synanton.gpu.client.GpuPlaneRerankClient(props, objectMapper, rerank.getPromptFormat(),
                rerank.getInstruction());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "synquest.rerank.enabled", havingValue = "true")
    public org.synanton.synquest.service.SearchReranker searchReranker(org.synanton.gpu.client.GpuPlaneRerankClient client,
                                                                       SynquestRerankProperties rerank) {
        return new org.synanton.synquest.service.SearchReranker() {
            @Override
            public double[] scores(String tenant, String query, java.util.List<String> passages) {
                var res = client.rerank(new org.synanton.llm.RerankRequest(rerank.getModel(), query, passages, 0), tenant);
                double[] out = new double[passages.size()];
                res.results().forEach(r -> out[r.index()] = r.score());
                return out;
            }

            @Override
            public String model() {
                return rerank.getModel();
            }
        };
    }
}
