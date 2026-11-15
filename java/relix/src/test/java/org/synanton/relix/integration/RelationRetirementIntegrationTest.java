package org.synanton.relix.integration;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.synanton.relix.adapter.out.graph.neo4j.DriverCypherExecutor;
import org.synanton.relix.adapter.out.graph.neo4j.Neo4jGraphConnector;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #655 acceptance matrix for evidence retirement and derived assertion state.
 */
class RelationRetirementIntegrationTest {

    private static Neo4jContainer<?> neo4j;
    private static Driver driver;
    private static DriverCypherExecutor cypher;
    private static Neo4jGraphConnector graph;

    @BeforeAll
    static void startNeo4j() {
        neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26.32")).withoutAuthentication();
        neo4j.start();
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.none());
        cypher = new DriverCypherExecutor(driver, "neo4j");
        graph = new Neo4jGraphConnector(null, cypher);
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

    @Test
    void staleReplacementRetiresEvidenceAndUnsupportedAssertion() {
        String tenant = "tenant-655-stale";
        String relation = "rel-655-stale";
        seed(tenant, relation, "ev-stale-v1", "source-a", "v1", 1, "SUPPORTS", "CURRENT");

        graph.retireSupersededEvidence(tenant, "source-a", 2);

        assertThat(evidenceStatus(tenant, "ev-stale-v1"))
                .as("V1 evidence must be superseded")
                .isEqualTo("SUPERSEDED");
        Map<String, Object> rel = relationState(tenant, relation);
        assertThat(rel.get("status"))
                .as("no current support remains, so the assertion is unsupported")
                .isEqualTo("UNSUPPORTED");
        assertThat(rel.get("active")).isEqualTo(false);
    }

    @Test
    void sharedSupportKeepsAssertionActive() {
        String tenant = "tenant-655-shared";
        String relation = "rel-655-shared";
        seed(tenant, relation, "ev-shared-v1", "source-a", "v1", 1, "SUPPORTS", "CURRENT");
        seed(tenant, relation, "ev-shared-v3", "source-b", "v3", 1, "SUPPORTS", "CURRENT");

        graph.retireSupersededEvidence(tenant, "source-a", 2);

        assertThat(evidenceStatus(tenant, "ev-shared-v1")).isEqualTo("SUPERSEDED");
        assertThat(evidenceStatus(tenant, "ev-shared-v3")).isEqualTo("CURRENT");
        Map<String, Object> rel = relationState(tenant, relation);
        assertThat(rel.get("status")).isEqualTo("ACTIVE");
        assertThat(rel.get("active")).isEqualTo(true);
    }

    @Test
    void conflictingCurrentSourcesProduceConflictedAssertion() {
        String tenant = "tenant-655-conflict";
        String relation = "rel-655-conflict";
        seed(tenant, relation, "ev-conflict-support", "source-a", "v2", 2, "SUPPORTS", "CURRENT");
        seed(tenant, relation, "ev-conflict-contradict", "source-b", "v1", 1, "CONTRADICTS", "CONTRADICTING");

        graph.recomputeAssertionState(tenant, relation);

        Map<String, Object> rel = relationState(tenant, relation);
        assertThat(rel.get("status"))
                .as("support plus a current contradiction is CONFLICTED, not active or retired")
                .isEqualTo("CONFLICTED");
        assertThat(rel.get("active")).isEqualTo(false);
        assertThat(rel.get("review_required")).isEqualTo(true);
        assertThat(evidenceStatus(tenant, "ev-conflict-support")).isEqualTo("CURRENT");
        assertThat(evidenceStatus(tenant, "ev-conflict-contradict")).isEqualTo("CONTRADICTING");
    }

    private static void seed(String tenant, String relation, String evidence, String sourceId,
                             String versionId, long generation, String polarity, String status) {
        cypher.write("""
                MERGE (s:Entity {tenant: $tenant, id: 'subject'})
                MERGE (o:Entity {tenant: $tenant, id: 'object'})
                MERGE (s)-[r:REL {tenant_id: $tenant, canonical_relation_id: $relation}]->(o)
                SET r.predicate = 'requires', r.status = 'ACTIVE', r.active = true,
                    r.review_required = false
                MERGE (e:RelationEvidence {tenant_id: $tenant, evidence_id: $evidence})
                SET e.canonical_relation_id = $relation,
                    e.source_id = $sourceId,
                    e.source_version_id = $versionId,
                    e.generation = $generation,
                    e.polarity = $polarity,
                    e.status = $status,
                    e.evidence_chunk_id = $evidence + ':0'
                """, Map.of(
                "tenant", tenant,
                "relation", relation,
                "evidence", evidence,
                "sourceId", sourceId,
                "versionId", versionId,
                "generation", generation,
                "polarity", polarity,
                "status", status));
    }

    private static String evidenceStatus(String tenant, String evidence) {
        return String.valueOf(cypher.read("""
                MATCH (e:RelationEvidence {tenant_id: $tenant, evidence_id: $evidence})
                RETURN e.status AS status
                """, Map.of("tenant", tenant, "evidence", evidence))
                .getFirst().get("status"));
    }

    private static Map<String, Object> relationState(String tenant, String relation) {
        return cypher.read("""
                MATCH ()-[r:REL {tenant_id: $tenant, canonical_relation_id: $relation}]->()
                RETURN r.status AS status, r.active AS active, r.review_required AS review_required
                """, Map.of("tenant", tenant, "relation", relation)).getFirst();
    }
}
