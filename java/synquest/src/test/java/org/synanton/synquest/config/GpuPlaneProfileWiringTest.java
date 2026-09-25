package org.synanton.synquest.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.synanton.gpu.client.GpuPlaneEmbedClient;
import org.synanton.llm.HttpLlmClient;
import org.synanton.llm.LlmClient;

import static org.assertj.core.api.Assertions.assertThat;

/** The gpu-plane profile swaps the HTTP embed client for the fail-closed GPU plane client. */
class GpuPlaneProfileWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withUserConfiguration(SynquestConfig.class, GpuPlaneEmbeddingConfig.class);

    @Test
    void defaultProfileKeepsTheHttpClient() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(LlmClient.class);
            assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(HttpLlmClient.class);
        });
    }

    @Test
    void gpuPlaneProfileUsesTheGpuPlaneClient() {
        runner.withPropertyValues("spring.profiles.active=gpu-plane",
                        "gpu-plane.endpoint=localhost:1", "gpu-plane.tls.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(LlmClient.class);
                    assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(GpuPlaneEmbedClient.class);
                });
    }

    @Test
    void embeddingDimAndTruncationBindFromConfiguration() {
        runner.withBean(org.synanton.synquest.service.EmbeddingShape.class)
                .withPropertyValues("synquest.embedding.model=m", "synquest.embedding.dim=1024",
                        "synquest.embedding.truncate-dim=1024", "synquest.embedding.normalise-l2=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var shape = ctx.getBean(org.synanton.synquest.service.EmbeddingShape.class);
                    assertThat(shape.dim()).isEqualTo(1024);
                    assertThat(shape.truncates()).isTrue();
                });
    }

    @Test
    void dimAboveTheLuceneCapFailsAtStartup() {
        runner.withBean(org.synanton.synquest.service.EmbeddingShape.class)
                .withPropertyValues("synquest.embedding.model=m", "synquest.embedding.dim=2048")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void rerankBeansExistOnlyWhenEnabledUnderGpuPlane() {
        runner.withPropertyValues("spring.profiles.active=gpu-plane", "gpu-plane.endpoint=localhost:1",
                        "gpu-plane.tls.enabled=false", "synquest.rerank.enabled=true", "synquest.rerank.prompt-format=QWEN3")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(org.synanton.gpu.client.GpuPlaneRerankClient.class);
                    assertThat(ctx.getBean(org.synanton.synquest.service.SearchReranker.class).model())
                            .isEqualTo("synanton-qwen3-reranker-0.6b");
                });
        runner.withPropertyValues("spring.profiles.active=gpu-plane", "gpu-plane.endpoint=localhost:1",
                        "gpu-plane.tls.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(org.synanton.synquest.service.SearchReranker.class));
    }

    @Test
    void gpuPlaneProfileWithMissingTlsFilesFailsAtStartup() {
        runner.withPropertyValues("spring.profiles.active=gpu-plane", "gpu-plane.tls.enabled=true",
                        "gpu-plane.tls.ca-path=/nonexistent/ca.crt")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
