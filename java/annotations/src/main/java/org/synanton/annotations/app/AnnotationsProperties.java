package org.synanton.annotations.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "annotations")
public record AnnotationsProperties(Equalix equalix) {

    /** Worker-pool sizing for AAP-2's controlled recalculation execution (design §50, §62). */
    public record Equalix(int poolSize, long pollTimeoutMs) {}

    public AnnotationsProperties {
        if (equalix == null) {
            equalix = new Equalix(4, 200);
        }
    }
}
