package org.synanton.synquest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Resolution consumer tuning (PR #4d.1). Disabled until Relix is deployed. */
@ConfigurationProperties(prefix = "synquest.resolution.consumer")
public record ResolutionConsumerProperties(
        boolean enabled,
        String tenant,
        Integer batchMentions,
        Integer maxAttempts,
        Integer retryBackoffSeconds,
        Integer leaseSeconds,
        Long pollDelayMs,
        String ontologyVersion) {

    public int batchMentionsOrDefault() {
        return batchMentions == null ? 10 : Math.max(1, batchMentions);
    }

    public int maxAttemptsOrDefault() {
        return maxAttempts == null ? 5 : Math.max(1, maxAttempts);
    }

    public int retryBackoffSecondsOrDefault() {
        return retryBackoffSeconds == null ? 30 : Math.max(1, retryBackoffSeconds);
    }

    public int leaseSecondsOrDefault() {
        return leaseSeconds == null ? 300 : Math.max(1, leaseSeconds);
    }

    public String ontologyVersionOrDefault() {
        return ontologyVersion == null || ontologyVersion.isBlank() ? "active" : ontologyVersion;
    }
}
