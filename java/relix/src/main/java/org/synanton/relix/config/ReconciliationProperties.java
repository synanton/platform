package org.synanton.relix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** PR #578 crash reconciliation; disabled until explicitly validated. */
@ConfigurationProperties(prefix = "relix.reconciliation")
public record ReconciliationProperties(
        boolean enabled,
        String tenant,
        Integer batch,
        Long pollDelayMs,
        Integer leaseSeconds) {

    public int batchOrDefault() {
        return batch == null ? 100 : Math.max(1, batch);
    }

    public int leaseSecondsOrDefault() {
        return leaseSeconds == null ? 120 : Math.max(1, leaseSeconds);
    }
}
