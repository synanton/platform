package org.synanton.relix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relix")
public record RelixProperties(
        Graph graph,
        Query query
) {
    public record Graph(boolean loadOnBoot, String tenant) {}

    public record Query(
            int entityLookupDefaultLimit,
            int oneHopDefaultLimit,
            int kHopMaxHopsCap,
            int kHopMaxPathsCap
    ) {}

    public RelixProperties {
        if (graph == null) graph = new Graph(true, "demo");
        if (query == null) query = new Query(10, 50, 6, 100);
    }
}
