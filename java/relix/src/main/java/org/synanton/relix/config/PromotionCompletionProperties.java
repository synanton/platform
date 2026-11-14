package org.synanton.relix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded canonical graph completion sweep. Disabled by default. */
@ConfigurationProperties(prefix = "relix.promotion-completion")
public record PromotionCompletionProperties(
        Boolean enabled,
        String tenant,
        Integer batch,
        Long pollDelayMs) {

    public PromotionCompletionProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (batch == null || batch < 1) {
            batch = 100;
        }
        if (pollDelayMs == null || pollDelayMs < 1) {
            pollDelayMs = 30_000L;
        }
    }
}
