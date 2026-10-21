package org.synanton.synquest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.synanton.gpu.client.GpuPlaneClientProperties;
import org.synanton.gpu.client.GpuPlaneJwtProvider;

import java.util.Optional;

/**
 * Makes the execution-JWT state observable and, when required, fails startup before the
 * service can issue unsigned GPU requests.
 *
 * <p>With {@code gpu-plane.jwt-required=true}, a missing provider/token is a startup
 * failure. With it false (dev/loopback), the absence is a WARN: requests are UNSIGNED and a
 * gateway enforcing execution-JWT auth will reject them.
 */
@Component
@Profile("gpu-plane")
public class GpuPlaneJwtStartupCheck implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(GpuPlaneJwtStartupCheck.class);

    private final GpuPlaneClientProperties properties;
    private final Optional<GpuPlaneJwtProvider> provider;

    public GpuPlaneJwtStartupCheck(
            GpuPlaneClientProperties properties,
            Optional<GpuPlaneJwtProvider> provider) {
        this.properties = properties;
        this.provider = provider;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        verify();
    }

    void verify() {
        String token = provider.map(GpuPlaneJwtProvider::token).orElse(null);
        boolean present = token != null && !token.isBlank();
        if (present) {
            log.info("gpu-plane execution JWT present (signer configured)");
            return;
        }
        if (properties.isJwtRequired()) {
            throw new IllegalStateException(
                    "gpu-plane.jwt-required=true but no GpuPlaneJwtProvider/token is configured");
        }
        log.warn("gpu-plane JWT absent: GPU requests will be UNSIGNED; a gateway with "
                + "execution-JWT auth enabled will reject them with UNAUTHENTICATED");
    }
}
