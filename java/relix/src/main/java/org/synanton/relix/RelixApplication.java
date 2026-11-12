package org.synanton.relix;

import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.service.GraphQueryService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {
        "org.synanton.relix",
        "org.synanton.ingestioncache"
})
public class RelixApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelixApplication.class, args);
    }

    @Bean
    ApplicationRunner graphInitializer(GraphQueryService graphQueryService,
                                       GraphConnector graphConnector) {
        return args -> {
            // Neo4j is the canonical graph store. Its loadTenant implementation
            // rebuilds from Cassandra and deletes existing :Entity/:REL data,
            // which is destructive on every pod restart. Only load projection
            // stores (memory/nebula) at startup; Neo4j must start in place.
            if (!"neo4j".equals(graphConnector.id())) {
                // Projection provisioning is a separate lifecycle from runtime
                // graph access (ADR-027); this local demo load is not a query path.
                graphConnector.loadTenant("demo");
            }
        };
    }
}
