package org.synanton.synquest.config;

import org.junit.jupiter.api.Test;
import org.synanton.gpu.client.GpuPlaneClientProperties;
import org.synanton.gpu.client.GpuPlaneJwtProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GpuPlaneJwtStartupCheckTest {

    private static GpuPlaneClientProperties properties(boolean required) {
        GpuPlaneClientProperties props = new GpuPlaneClientProperties();
        props.setJwtRequired(required);
        return props;
    }

    @Test
    void failsWhenRequiredAndNoSignerIsConfigured() {
        GpuPlaneJwtStartupCheck check =
                new GpuPlaneJwtStartupCheck(properties(true), Optional.empty());

        assertThatThrownBy(check::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt-required=true");
    }

    @Test
    void warnsWithoutFailingWhenOptionalAndNoSignerIsConfigured() {
        GpuPlaneJwtStartupCheck check =
                new GpuPlaneJwtStartupCheck(properties(false), Optional.empty());

        assertThatCode(check::verify).doesNotThrowAnyException();
    }

    @Test
    void passesWhenTheProviderReturnsAToken() {
        GpuPlaneJwtProvider provider = () -> "token-123";
        GpuPlaneJwtStartupCheck check =
                new GpuPlaneJwtStartupCheck(properties(true), Optional.of(provider));

        assertThatCode(check::verify).doesNotThrowAnyException();
    }
}
