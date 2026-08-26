package org.synanton.relix.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.relix.adapter.out.graph.memory.InMemoryGraphConnector;
import org.synanton.relix.adapter.out.graph.nebula.NebulaGraphConnector;
import org.synanton.relix.adapter.out.graph.nebula.NebulaSession;
import org.synanton.relix.adapter.out.graph.nebula.UnconfiguredNebulaSession;
import org.synanton.relix.adapter.out.graph.neo4j.CypherExecutor;
import org.synanton.relix.adapter.out.graph.neo4j.DriverCypherExecutor;
import org.synanton.relix.adapter.out.graph.neo4j.Neo4jGraphConnector;
import org.synanton.relix.graph.GraphConnector;
import org.synanton.relix.graph.GraphLoader;

@Configuration
@EnableConfigurationProperties(RelixProperties.class)
public class RelixConfig {

    @Bean
    @ConditionalOnProperty(name = "relix.graph.connector", havingValue = "memory", matchIfMissing = true)
    GraphConnector inMemoryGraphConnector(GraphLoader graphLoader) {
        return new InMemoryGraphConnector(graphLoader);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "relix.graph.connector", havingValue = "neo4j")
    Driver neo4jDriver(RelixProperties props) {
        RelixProperties.Neo4j neo4j = props.graph().neo4j();
        if (neo4j.uri() == null || neo4j.uri().isBlank()) {
            throw new IllegalStateException("relix.graph.neo4j.uri is required when relix.graph.connector=neo4j");
        }
        String password = neo4j.password() == null ? "" : neo4j.password();
        return GraphDatabase.driver(neo4j.uri(), AuthTokens.basic(neo4j.username(), password));
    }

    @Bean
    @ConditionalOnProperty(name = "relix.graph.connector", havingValue = "neo4j")
    CypherExecutor cypherExecutor(Driver neo4jDriver, RelixProperties props) {
        return new DriverCypherExecutor(neo4jDriver, props.graph().neo4j().database());
    }

    @Bean
    @ConditionalOnProperty(name = "relix.graph.connector", havingValue = "neo4j")
    GraphConnector neo4jGraphConnector(GraphLoader graphLoader, CypherExecutor cypherExecutor) {
        return new Neo4jGraphConnector(graphLoader, cypherExecutor);
    }

    @Bean
    @ConditionalOnProperty(name = "relix.graph.connector", havingValue = "nebula")
    @ConditionalOnMissingBean(NebulaSession.class)
    NebulaSession nebulaSession() {
        return new UnconfiguredNebulaSession();
    }

    @Bean
    @ConditionalOnProperty(name = "relix.graph.connector", havingValue = "nebula")
    GraphConnector nebulaGraphConnector(GraphLoader graphLoader, NebulaSession nebulaSession, RelixProperties props) {
        return new NebulaGraphConnector(graphLoader, nebulaSession, props.graph().nebula().space());
    }
}
