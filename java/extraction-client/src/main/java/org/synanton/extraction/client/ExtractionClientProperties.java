package org.synanton.extraction.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synanton.extraction.client")
public record ExtractionClientProperties(
        boolean enabled,
        String endpoint,
        String mode,
        int deadlineSeconds,
        int pollIntervalSeconds,
        String fallback,
        String defaultPriority
) {
    public boolean isAsyncMode() {
        return "async".equalsIgnoreCase(mode);
    }

    public ExtractionFallbackPolicy fallbackPolicy() {
        return ExtractionFallbackPolicy.fromConfig(fallback);
    }
}
