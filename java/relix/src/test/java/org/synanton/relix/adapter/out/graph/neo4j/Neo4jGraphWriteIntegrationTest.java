package org.synanton.relix.adapter.out.graph.neo4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.ExtractedRelation;
import org.synanton.relix.graph.GraphLoader;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

/** Real Neo4j persistence/idempotency test for the isolated extracted-mention graph. */
class Neo4jGraphWriteIntegrationTest {

    private static final String TENANT = "tenant-graph-write";
    private static Neo4jContainer<?> neo4j;
    private static Driver driver;
    private static CypherExecutor cypher;
    private static Neo4jGraphConnector connector;

    @BeforeAll
    static void startNeo4j() {
        neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26")).withoutAuthentication();
        neo4j.start();
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.none());
        cypher = new SessionCypher(driver);
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

    @Test
    void writesMentionsAndValidatedRelationsIdempotentlyWithoutCanonicalEntities() {
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        ExtractedMention subject = new ExtractedMention(
                "mention-subject", TENANT, "version-1", "content-ref", "content-ref:0",
                0, 9, 100, 109, "Halcyon-7", "MaintenanceSystem", now);
        ExtractedMention object = new ExtractedMention(
                "mention-object", TENANT, "version-1", "content-ref", "content-ref:0",
                23, 40, 123, 140, "helium leak check", "Procedure", now);
        ExtractedRelation relation = new ExtractedRelation(
                "relation-1", TENANT, "version-1", "requires", subject.mentionId(), object.mentionId(),
                "content-ref:0", 0, 41, 100, 141, now);

        connector.upsertMentions(List.of(subject, object));
        connector.upsertMentions(List.of(subject, object));
        connector.upsertRelations(List.of(relation));
        connector.upsertRelations(List.of(relation));

        assertThat(count("MATCH (n:ExtractedMention {tenant_id: $tenant}) RETURN count(n) AS c")).isEqualTo(2);
        assertThat(count("MATCH ()-[e:EXTRACTED_RELATION {tenant_id: $tenant}]->() RETURN count(e) AS c")).isEqualTo(1);
        assertThat(count("MATCH (n:Entity) RETURN count(n) AS c")).isZero();
        assertThat(count("MATCH ()-[e:REL]->() RETURN count(e) AS c")).isZero();

        Map<String, Object> offsets = cypher.read(
                "MATCH (n:ExtractedMention {mention_id: $id}) RETURN n.chunk_offset_start AS chunkStart, "
                        + "n.document_offset_start AS documentStart",
                Map.of("id", subject.mentionId())).getFirst();
        assertThat(((Number) offsets.get("chunkStart")).intValue()).isZero();
        assertThat(((Number) offsets.get("documentStart")).intValue()).isEqualTo(100);
    }

    private static long count(String query) {
        Object value = cypher.read(query, Map.of("tenant", TENANT)).getFirst().get("c");
        return ((Number) value).longValue();
    }

    private record SessionCypher(Driver driver) implements CypherExecutor {
        @Override
        public List<Map<String, Object>> read(String cypher, Map<String, Object> params) {
            try (Session session = driver.session()) {
                return session.executeRead(tx -> {
                    var result = tx.run(cypher, params);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (result.hasNext()) {
                        rows.add(result.next().asMap());
                    }
                    return rows;
                });
            }
        }

        @Override
        public void write(String cypher, Map<String, Object> params) {
            try (Session session = driver.session()) {
                session.executeWrite(tx -> {
                    tx.run(cypher, params).consume();
                    return null;
                });
            }
        }
    }
}
