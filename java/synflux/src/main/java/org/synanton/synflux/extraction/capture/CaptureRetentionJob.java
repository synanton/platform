package org.synanton.synflux.extraction.capture;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.extraction.engine.capture.FileSystemCaptureStore;
import org.synanton.synflux.config.ExtractionEngineProperties;

/**
 * #830 retention slice: default 30-day expiry for diagnostic capture files.
 * Capture remains disabled by default and is refused in production until the
 * remaining D5 controls (encryption/KMS, audit, full compliance wiring) land.
 * The hard 90-day maximum is enforced by never extending the 30-day cutoff.
 */
@Component
public class CaptureRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(CaptureRetentionJob.class);
    static final Duration DEFAULT_RETENTION = Duration.ofDays(30);

    private final ExtractionEngineProperties properties;
    private final Clock clock;

    public CaptureRetentionJob(ExtractionEngineProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${synflux.extraction-engine.capture-retention-cron:0 0 3 * * *}")
    public void run() {
        if (!Boolean.TRUE.equals(properties.captureEnabled())) {
            return;
        }
        if (properties.captureEnvironment() != null
                && "production".equalsIgnoreCase(properties.captureEnvironment().trim())) {
            return;
        }
        if (properties.captureDir() == null || properties.captureDir().isBlank()) {
            return;
        }
        FileSystemCaptureStore store = new FileSystemCaptureStore(Path.of(properties.captureDir()));
        int expired = store.expireOlderThan(clock.instant().minus(DEFAULT_RETENTION));
        if (expired > 0) {
            log.info("Expired {} diagnostic capture file(s) older than {} days",
                    expired, DEFAULT_RETENTION.toDays());
        }
    }
}
