package org.synanton.router;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synflux-router.kafka")
public record RouterProperties(
        String ingestionRequestsTopic,
        String ingestionEventsTopic,
        int pauseQueueMaxSize,
        int producerRetryAttempts,
        long producerRetryBackoffMs
) {
    public RouterProperties {
        if (ingestionRequestsTopic == null) ingestionRequestsTopic = "ingestion_requests";
        if (ingestionEventsTopic == null) ingestionEventsTopic = "ingestion_events";
        if (pauseQueueMaxSize <= 0) pauseQueueMaxSize = 1000;
        if (producerRetryAttempts <= 0) producerRetryAttempts = 3;
        if (producerRetryBackoffMs <= 0) producerRetryBackoffMs = 100;
    }
}
