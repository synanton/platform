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

        assertThat(connector.upsertMentions(List.of(subject, object))).isEqualTo(2);
        assertThat(connector.upsertMentions(List.of(subject, object))).isEqualTo(2);
        assertThat(connector.upsertRelations(List.of(relation))).isEqualTo(1);
        assertThat(connector.upsertRelations(List.of(relation))).isEqualTo(1);

        assertThat(count("MATCH (n:ExtractedMention {tenant_id: $tenant}) RETURN count(n) AS c")).isEqualTo(2);
        assertThat(count("MATCH ()-[e:EXTRACTED_RELATION {tenant_id: $tenant}]->() RETURN count(e) AS c")).isEqualTo(1);
        assertThat(count("MATCH (n:Entity {tenant: $tenant}) RETURN count(n) AS c")).isZero();
        assertThat(count("MATCH ()-[e:REL]->() RETURN count(e) AS c")).isZero();

        Map<String, Object> offsets = cypher.read(
                "MATCH (n:ExtractedMention {mention_id: $id}) RETURN n.chunk_offset_start AS chunkStart, "
                        + "n.document_offset_start AS documentStart",
                Map.of("id", subject.mentionId())).getFirst();
        assertThat(((Number) offsets.get("chunkStart")).intValue()).isZero();
        assertThat(((Number) offsets.get("documentStart")).intValue()).isEqualTo(100);
    }

    @Test
    void canonicalLookupReturnsTheActiveGraphType() {
        String tenant = TENANT + "-lookup";
        cypher.write("CREATE (n:Entity {tenant: $tenant, id: 'entity-lookup', "
                + "label: 'Halcyon-7', type: 'MaintenanceSystem'})", Map.of("tenant", tenant));
        assertThat(connector.getActiveEntityType(tenant, "entity-lookup"))
                .contains("MaintenanceSystem");
        assertThat(connector.getActiveEntityType(tenant, "missing")).isEmpty();
    }

    @Test
    void missingRelationshipEndpointIsReportedAsNotPersisted() {
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        String tenant = TENANT + "-orphan";
        ExtractedMention subject = new ExtractedMention(
                "orphan-subject", tenant, "version-o", "ref", "ref:0",
                0, 9, 0, 9, "Subject", "Concept", now);
        connector.upsertMentions(List.of(subject));
        ExtractedRelation orphan = new ExtractedRelation(
                "orphan-relation", tenant, "version-o", "requires", subject.mentionId(),
                "missing-object", "ref:0", 0, 9, 0, 9, now);

        assertThat(connector.upsertRelations(List.of(orphan))).isZero();
    }

    @Test
    void concurrentMentionWritesAreUnique() throws Exception {
        String tenant = TENANT + "-concurrent";
        ExtractedMention mention = new ExtractedMention(
                "mention-concurrent", tenant, "version-c", "content-ref", "content-ref:0",
                0, 9, 0, 9, "Concurrent", "Concept", Instant.parse("2026-11-08T00:00:00Z"));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> connector.upsertMentions(List.of(mention))));
            }
            for (var future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        Object value = cypher.read("MATCH (n:ExtractedMention {tenant_id: $tenant, "
                + "mention_id: 'mention-concurrent'}) RETURN count(n) AS c", Map.of("tenant", tenant))
                .getFirst().get("c");
        assertThat(((Number) value).longValue()).isEqualTo(1);
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
