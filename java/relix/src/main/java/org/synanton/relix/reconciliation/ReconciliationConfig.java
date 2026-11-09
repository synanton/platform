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
 * PR #578/#585 wiring: enabling reconciliation makes the configured graph
 * deployment assumption explicit. A missing seam is a startup failure, not a
 * silent idle poller. This is not a capability model; #587 formalizes that.
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
        if (!(graph instanceof ResolutionReconciliationGraph seam)) {
            throw new IllegalStateException(
                    "relix.reconciliation.enabled=true requires the configured graph adapter to "
                            + "provide ResolutionReconciliationGraph; transitional Neo4j assumption "
                            + "#591, migrated under #587/#590");
        }
        return new ResolutionReconciler(cache, claims, seam, properties, registry);
    }
}
