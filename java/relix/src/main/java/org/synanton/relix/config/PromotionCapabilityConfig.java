package org.synanton.relix.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.relix.graph.GraphConnector;

/** Fails startup when promotion is enabled on an adapter without promotion semantics. */
@Configuration
@ConditionalOnProperty(name = "relix.promotion-consumer.enabled", havingValue = "true")
public class PromotionCapabilityConfig {

    @Bean
    ApplicationRunner promotionCapabilityCheck(GraphConnector graph) {
        return args -> {
            if (!graph.capabilities().supportsRelationPromotion()) {
                throw new IllegalStateException(
                        "relix.promotion-consumer.enabled=true requires conditional writes, "
                                + "atomic multi-node writes, and idempotent upsert; see #587/#592");
            }
        };
    }
}
