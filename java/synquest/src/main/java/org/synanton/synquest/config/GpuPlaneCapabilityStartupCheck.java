package org.synanton.synquest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.synanton.gpu.client.GpuPlaneClientProperties;
import org.synanton.gpu.client.GpuPlaneEmbedClient;
import org.synanton.gpu.v1.ModelInfo;

import java.util.List;

/**
 * Verifies the GPU gateway's EMBED capability at startup under the {@code gpu-plane}
 * profile: the configured logical model must be advertised and its measured
 * {@code embedding_dim} must match {@code EMBED_DIM}. A mismatch fails startup rather than
 * writing vectors of an unexpected size.
 *
 * <p>This is the same hard-fail discipline as {@code EmbeddingShape} and
 * {@code PersistenceStartupProbe}, with the GPU gateway as the source of truth.
 */
@Component
@Profile("gpu-plane")
public class GpuPlaneCapabilityStartupCheck implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(GpuPlaneCapabilityStartupCheck.class);

    private final GpuPlaneEmbedClient client;
    private final GpuPlaneClientProperties gpuProperties;
    private final SynquestProperties properties;

    public GpuPlaneCapabilityStartupCheck(
            GpuPlaneEmbedClient client,
            GpuPlaneClientProperties gpuProperties,
            SynquestProperties properties) {
        this.client = client;
        this.gpuProperties = gpuProperties;
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        verify();
    }

    void verify() {
        if (!gpuProperties.isCapabilityCheck()) {
            log.info("GPU embedding capability check skipped (gpu-plane.capability-check=false); "
                    + "vector queries fail closed until the gateway is available");
            return;
        }
        String expectedModel = properties.embedding().model();
        int expectedDim = properties.embedding().dim();
        List<ModelInfo> models = client.models(gpuProperties.getDefaultTenant());
        ModelInfo model = models.stream()
                .filter(candidate -> expectedModel.equals(candidate.getModelId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "GPU gateway does not advertise EMBED model '" + expectedModel + "'; advertised="
                                + models.stream().map(ModelInfo::getModelId).toList()));
        if (model.getEmbeddingDim() != expectedDim) {
            throw new IllegalStateException(
                    "GPU gateway model '" + expectedModel + "' reports embedding_dim="
                            + model.getEmbeddingDim() + " but EMBED_DIM=" + expectedDim);
        }
        log.info("GPU embedding capability model={} dimension={} backend=gateway",
                expectedModel, model.getEmbeddingDim());
    }
}
