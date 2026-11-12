package org.synanton.relix.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.relix.config.ReconciliationProperties;
import org.synanton.relix.graph.GraphConnector;

/**
 * Reconciliation wiring: enabling reconciliation requires the configured graph
 * adapter to advertise exact-identity reconciliation support. Missing capability
 * is a startup failure, not a silent idle poller.
 */
@Configuration
@ConditionalOnProperty(name = "relix.reconciliation.enabled", havingValue = "true")
public class ReconciliationConfig {

    @Bean
    ResolutionReconciler resolutionReconciler(
            IngestionCacheClient cache, ClaimService claims,
            ObjectProvider<GraphConnector> configuredGraph,
            ReconciliationProperties properties, MeterRegistry registry) {
        GraphConnector graph = configuredGraph.getIfAvailable();
        if (graph == null) {
            throw new IllegalStateException(
                    "relix.reconciliation.enabled=true requires a configured graph adapter");
        }
        if (!graph.capabilities().compositionIsConsistent()) {
            throw new IllegalStateException(
                    "relix.reconciliation.enabled=true requires consistent graph capabilities");
        }
        if (!graph.capabilities().supportsResolutionReconciliation()) {
            throw new IllegalStateException(
                    "relix.reconciliation.enabled=true requires conditional writes and "
                            + "idempotent upsert for resolution reconciliation; see #587/#592");
        }
        return new ResolutionReconciler(cache, claims, graph, properties, registry);
    }
}
