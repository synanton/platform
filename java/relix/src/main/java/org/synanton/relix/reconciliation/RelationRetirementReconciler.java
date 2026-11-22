package org.synanton.relix.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.client.TenantPolling;
import org.synanton.relix.config.ReconciliationProperties;
import org.synanton.relix.graph.RelationRetirement;

/**
 * #655: bounded, watermark-driven sweep that repairs only the derived {@code :REL}
 * assertion state. It never mutates evidence; evidence changes are authoritative.
 */
@Component
@ConditionalOnProperty(name = "relix.reconciliation.enabled", havingValue = "true")
public final class RelationRetirementReconciler {

    private static final Logger log = LoggerFactory.getLogger(RelationRetirementReconciler.class);

    private final ObjectProvider<RelationRetirement> retirement;
    private final ReconciliationProperties properties;
    private final IngestionCacheClient cache;

    public RelationRetirementReconciler(ObjectProvider<RelationRetirement> retirement,
                                        ReconciliationProperties properties,
                                        IngestionCacheClient cache) {
        this.retirement = retirement;
        this.properties = properties;
        this.cache = cache;
    }

    @Scheduled(fixedDelayString = "${relix.reconciliation.poll-delay-ms:60000}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        List<String> tenants;
        try {
            tenants = TenantPolling.resolve(properties.tenant(), cache);
        } catch (RuntimeException failure) {
            log.warn("Relation retirement tenant discovery failed: {}", failure.toString());
            return;
        }
        RelationRetirement relationRetirement = retirement.getIfAvailable();
        if (relationRetirement == null) {
            return;
        }
        for (String tenant : tenants) {
            try {
                relationRetirement.reconcileRetirementState(tenant, properties.batchOrDefault());
            } catch (RuntimeException failure) {
                log.warn("Relation retirement reconciliation failed tenant={}: {}",
                        tenant, failure.toString());
            }
        }
    }
}
