package org.synanton.synquest.config;

import org.junit.jupiter.api.Test;
import org.synanton.gpu.client.GpuPlaneClientProperties;
import org.synanton.gpu.client.GpuPlaneEmbedClient;
import org.synanton.gpu.v1.ModelInfo;
import org.synanton.gpu.v1.Operation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GpuPlaneCapabilityStartupCheckTest {

    private static SynquestProperties properties(String model, int dim) {
        return new SynquestProperties(
                null,
                null,
                new SynquestProperties.Embedding(model, dim, true, 0),
                null);
    }

    private static GpuPlaneClientProperties gpuProperties() {
        GpuPlaneClientProperties props = new GpuPlaneClientProperties();
        props.setDefaultTenant("tenant-a");
        return props;
    }

    private static ModelInfo model(String id, int dim) {
        return ModelInfo.newBuilder()
                .setModelId(id)
                .setOperation(Operation.EMBED)
                .setEmbeddingDim(dim)
                .build();
    }

    @Test
    void acceptsTheAdvertisedModelAndDimension() {
        GpuPlaneEmbedClient client = mock(GpuPlaneEmbedClient.class);
        when(client.models("tenant-a")).thenReturn(List.of(model("bge-small-384d", 384)));

        GpuPlaneCapabilityStartupCheck check =
                new GpuPlaneCapabilityStartupCheck(client, gpuProperties(), properties("bge-small-384d", 384));

        assertThatCode(check::verify).doesNotThrowAnyException();
    }

    @Test
    void rejectsAModelTheGatewayDoesNotAdvertise() {
        GpuPlaneEmbedClient client = mock(GpuPlaneEmbedClient.class);
        when(client.models("tenant-a")).thenReturn(List.of(model("bge-base-768d", 768)));

        GpuPlaneCapabilityStartupCheck check =
                new GpuPlaneCapabilityStartupCheck(client, gpuProperties(), properties("bge-small-384d", 384));

        assertThatThrownBy(check::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not advertise");
    }

    @Test
    void rejectsADimensionMismatch() {
        GpuPlaneEmbedClient client = mock(GpuPlaneEmbedClient.class);
        when(client.models("tenant-a")).thenReturn(List.of(model("bge-small-384d", 768)));

        GpuPlaneCapabilityStartupCheck check =
                new GpuPlaneCapabilityStartupCheck(client, gpuProperties(), properties("bge-small-384d", 384));

        assertThatThrownBy(check::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embedding_dim=768")
                .hasMessageContaining("EMBED_DIM=384");
    }
}
