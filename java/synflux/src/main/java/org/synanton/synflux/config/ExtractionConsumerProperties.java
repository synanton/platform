package org.synanton.synflux.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Async extraction consumer tuning. Disabled by default; enable with
 * {@code EXTRACTION_CONSUMER_ENABLED=true} once the GPU plane is reachable.
 */
@ConfigurationProperties(prefix = "synflux.extraction-engine.consumer")
public record ExtractionConsumerProperties(
        boolean enabled,
        String tenant,
        Integer batchDocuments,
        Integer maxAttempts,
        Integer retryBackoffSeconds,
        Long pollDelayMs) {

    public int batchDocumentsOrDefault() {
        return batchDocuments == null ? 1 : Math.max(1, batchDocuments);
    }

    public int maxAttemptsOrDefault() {
        return maxAttempts == null ? 5 : Math.max(1, maxAttempts);
    }

    public int retryBackoffSecondsOrDefault() {
        return retryBackoffSeconds == null ? 30 : Math.max(1, retryBackoffSeconds);
    }
}
