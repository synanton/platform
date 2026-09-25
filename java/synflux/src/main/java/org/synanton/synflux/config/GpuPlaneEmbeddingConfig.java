package org.synanton.synflux.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.synanton.gpu.client.GpuPlaneClientProperties;
import org.synanton.gpu.client.GpuPlaneEmbedClient;

/**
 * Opt-in {@code gpu-plane} profile. Ingest embeddings go to the GPU plane over gRPC
 * {@code synanton.gpu.v1} with mTLS, and the job's tenant becomes the request's
 * {@code tenant_id}. This replaces the HTTP embed client. {@link SynfluxConfig#embedStage}
 * picks this bean up and runs {@code EmbedStage} fail-closed. See the retrieval benchmark
 * plan, §6 Phase B1-G.
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
    public GpuPlaneEmbedClient gpuPlaneEmbedClient(GpuPlaneClientProperties props, ObjectMapper objectMapper) {
        return new GpuPlaneEmbedClient(props, objectMapper);
    }
}
