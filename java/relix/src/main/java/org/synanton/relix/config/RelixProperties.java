package org.synanton.relix.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "relix")
public record RelixProperties(
        Graph graph,
        Query query
) {
    public record Graph(
            boolean loadOnBoot,
            String tenant,
            String connector,
            Neo4j neo4j,
            Nebula nebula
    ) {
        public Graph {
            if (tenant == null || tenant.isBlank()) {
                tenant = "demo";
            }
            if (connector == null || connector.isBlank()) {
                connector = "memory";
            }
            if (neo4j == null) {
                neo4j = new Neo4j("", "neo4j", "", "neo4j");
            }
            if (nebula == null) {
                nebula = new Nebula("", "root", "nebula", "synanton");
            }
        }
    }

    public record Neo4j(String uri, String username, String password, String database) {}

    public record Nebula(String graphdHosts, String username, String password, String space) {}

    public record Query(
            int entityLookupDefaultLimit,
            int oneHopDefaultLimit,
            int kHopMaxHopsCap,
            int kHopMaxPathsCap
    ) {}

    public RelixProperties {
        if (graph == null) {
            graph = new Graph(true, "demo", "memory", null, null);
        }
        if (query == null) {
            query = new Query(10, 50, 6, 100);
        }
    }
}
