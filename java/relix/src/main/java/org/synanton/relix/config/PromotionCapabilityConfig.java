package org.synanton.relix.config;

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
