package org.synanton.relix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Promotion consumer tuning (PR #4d.1). Disabled by default. */
@ConfigurationProperties(prefix = "relix.promotion-consumer")
public record PromotionConsumerProperties(
        boolean enabled,
        String tenant,
        Integer batchRelations,
        Integer maxAttempts,
        Integer retryBackoffSeconds,
        Integer leaseSeconds,
        Long pollDelayMs) {

    public int batchRelationsOrDefault() {
        return batchRelations == null ? 5 : Math.max(1, batchRelations);
    }

    public int maxAttemptsOrDefault() {
        return maxAttempts == null ? 5 : Math.max(1, maxAttempts);
    }

    public int retryBackoffSecondsOrDefault() {
        return retryBackoffSeconds == null ? 30 : Math.max(1, retryBackoffSeconds);
    }

    public int leaseSecondsOrDefault() {
        return leaseSeconds == null ? 120 : Math.max(1, leaseSeconds);
    }
}
