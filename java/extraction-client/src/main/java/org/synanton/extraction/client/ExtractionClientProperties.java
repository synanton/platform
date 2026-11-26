package org.synanton.extraction.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "synanton.extraction.client")
public record ExtractionClientProperties(
        boolean enabled,
        String endpoint,
        String mode,
        int deadlineSeconds,
        int pollIntervalSeconds,
        String defaultPriority
) {
    public boolean isAsyncMode() {
        return "async".equalsIgnoreCase(mode);
    }
}
