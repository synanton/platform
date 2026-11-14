package org.synanton.relix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded activation sweep configuration (ADR-029 §12.2 recovery). */
@ConfigurationProperties(prefix = "relix.activation-recovery")
public record ActivationRecoveryProperties(
        Boolean enabled,
        String tenant,
        Integer batch,
        Long pollDelayMs,
        Integer maxAttempts,
        Long retryBackoffSeconds) {

    public ActivationRecoveryProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (batch == null || batch < 1) {
            batch = 100;
        }
        if (pollDelayMs == null || pollDelayMs < 1) {
            pollDelayMs = 30_000L;
        }
        if (maxAttempts == null || maxAttempts < 1) {
            maxAttempts = 5;
        }
        if (retryBackoffSeconds == null || retryBackoffSeconds < 1) {
            retryBackoffSeconds = 30L;
        }
    }
}
