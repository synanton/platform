package org.synanton.relix.adapter.out.graph.neo4j;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.synanton.common.resolution.GraphCapabilities;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.resolution.StaleResolutionException;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.graph.AbstractGraphContractTest;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.GraphReadPort;
import org.synanton.relix.graph.GraphWritePort;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

class Neo4jGraphContractTest extends AbstractGraphContractTest {

    private static Neo4jContainer<?> neo4j;
    private static Driver driver;
    private static DriverCypherExecutor cypher;
    private static Neo4jGraphConnector connector;

    @BeforeAll
    static void startNeo4j() {
        neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26")).withoutAuthentication();
        neo4j.start();
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.none());
        cypher = new DriverCypherExecutor(driver, "neo4j");
        cypher.write("""
                CREATE CONSTRAINT contract_entity_unique IF NOT EXISTS
                FOR (e:Entity) REQUIRE (e.tenant, e.id) IS UNIQUE
                """, Map.of());
        connector = new Neo4jGraphConnector(mock(GraphLoader.class), cypher);
    }

    @AfterAll
    static void stopNeo4j() {
        if (driver != null) {
            driver.close();
        }
        if (neo4j != null) {
            neo4j.stop();
        }
    }

    @Override
    protected GraphReadPort readPort() {
        return connector;
    }

    @Override
    protected GraphWritePort writePort() {
        return connector;
    }

    @Override
    protected GraphCapabilities capabilities() {
        return GraphCapabilities.neo4j();
    }

    @Override
    protected void writeAtomicallyAndFail(GraphIdentity identity, Entity first, Entity second) {
        cypher.write("""
                CREATE (:Entity {tenant: $tenant, id: $id, label: $first, type: 'Thing',
                                 confidence: 1.0, sources: ''})
                CREATE (:Entity {tenant: $tenant, id: $id, label: $second, type: 'Thing',
                                 confidence: 1.0, sources: ''})
                """, Map.of("tenant", identity.tenantId(), "id", first.entityId().toString(),
                "first", first.label(), "second", second.label()));
    }

    @Override
    protected void writeWithFence(GraphIdentity identity, Entity entity, long fencingToken) {
        List<Map<String, Object>> rows = cypher.writeReturning("""
                MERGE (n:Entity {tenant: $tenant, id: $id})
                ON CREATE SET n.fence = 0
                WITH n
                WHERE coalesce(n.fence, 0) < $fence
                SET n.fence = $fence, n.label = $label, n.type = 'Thing',
                    n.confidence = 1.0, n.sources = ''
                RETURN n.fence AS fence
                """, Map.of("tenant", identity.tenantId(), "id", entity.entityId().toString(),
                "fence", fencingToken, "label", entity.label()));
        if (rows.isEmpty()) {
            throw new StaleResolutionException("stale fencing token");
        }
    }
}
