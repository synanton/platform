package org.synanton.relix.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.synanton.relix.graph.GraphConnector;

/**
 * Promotion is enabled only when the configured adapter declares the
 * capability composition required by the promotion workflow.
 */
@Configuration
@ConditionalOnProperty(name = "relix.promotion-consumer.enabled", havingValue = "true")
public class PromotionCapabilityConfig {

    public PromotionCapabilityConfig(ObjectProvider<GraphConnector> configuredGraph) {
        GraphConnector graph = configuredGraph.getIfAvailable();
        if (graph == null) {
            throw new IllegalStateException(
                    "relix.promotion-consumer.enabled=true requires a configured graph adapter");
        }
        if (!graph.capabilities().compositionIsConsistent()) {
            throw new IllegalStateException(
                    "relix.promotion-consumer.enabled=true requires consistent graph capabilities");
        }
        if (!graph.capabilities().supportsRelationPromotion()) {
            throw new IllegalStateException(
                    "relix.promotion-consumer.enabled=true requires conditional, atomic, "
                            + "idempotent relation promotion support; see #587/#592");
        }
    }
}
