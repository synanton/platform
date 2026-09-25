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
}
