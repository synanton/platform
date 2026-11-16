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
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.relix.config.ReconciliationProperties;
import org.synanton.relix.identity.SourceFenceKey;
import org.synanton.relix.identity.SourceFenceReconciler;

/**
 * ADR-029 §11: repair the derived Neo4j {@code :SourceRevisionFence} from the
 * authoritative Cassandra accepted generation. Promotion is fail-closed while the
 * fence is missing or behind, so this sweep is the prerequisite that lets a
 * committed revision promote after a Cassandra-only acceptance or a fence-write
 * failure. It never authorizes promotion on its own.
 */
@Component
@ConditionalOnProperty(name = "relix.reconciliation.enabled", havingValue = "true")
public final class SourceFenceReconciliationSweep {

    private static final Logger log = LoggerFactory.getLogger(SourceFenceReconciliationSweep.class);

    private final IngestionCacheClient cache;
    private final ObjectProvider<SourceFenceReconciler> reconciler;
    private final ReconciliationProperties properties;

    public SourceFenceReconciliationSweep(IngestionCacheClient cache,
                                          ObjectProvider<SourceFenceReconciler> reconciler,
                                          ReconciliationProperties properties) {
        this.cache = cache;
        this.reconciler = reconciler;
        this.properties = properties;
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
            log.warn("Source fence tenant discovery failed: {}", failure.toString());
            return;
        }
        SourceFenceReconciler fenceReconciler = reconciler.getIfAvailable();
        if (fenceReconciler == null) {
            return;
        }
        for (String tenant : tenants) {
            try {
                for (SourceRow source : cache.listSources(tenant)) {
                    if (cache.readAcceptedGeneration(tenant, source.sourceId()).isEmpty()) {
                        continue;
                    }
                    SourceFenceKey key = new SourceFenceKey(tenant, source.sourceId());
                    try {
                        fenceReconciler.reconcile(key);
                    } catch (RuntimeException failure) {
                        log.warn("Source fence reconciliation failed key={}: {}", key, failure.toString());
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("Source fence reconciliation scan failed tenant={}: {}", tenant, failure.toString());
            }
        }
    }
}
