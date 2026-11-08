package org.synanton.relix.adapter.out.graph.neo4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
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
    void resolvingARevisionSupersedesThePreviousActiveLink() {
        String tenant = TENANT + "-resolution";
        String mentionId = "mention-resolution";
        String version = "version-resolution";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        connector.upsertMentions(List.of(new ExtractedMention(mentionId, tenant, version, "ref", "ref:0",
                0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", now)));
        cypher.write("CREATE (n:Entity {tenant: $tenant, id: 'entity-v1', label: 'Halcyon-7', "
                + "type: 'MaintenanceSystem'})", Map.of("tenant", tenant));
        cypher.write("CREATE (n:Entity {tenant: $tenant, id: 'entity-v2', label: 'Halcyon-7', "
                + "type: 'MaintenanceSystem'})", Map.of("tenant", tenant));

        connector.upsertResolution(decision(tenant, version, mentionId, "entity-v1", "v1"));
        assertThat(resolutionEdges(tenant, mentionId)).singleElement().satisfies(edge -> {
            assertThat(edge.get("active")).isEqualTo(true);
            assertThat(edge.get("status")).isEqualTo("ACTIVE");
            assertThat(edge.get("target")).isEqualTo("entity-v1");
            assertThat(edge.get("policy")).isEqualTo("v1");
            assertThat(edge.get("retrieval")).isEqualTo("RRF");
            assertThat(edge.get("resolution_id")).isNotNull();
        });

        connector.upsertResolution(decision(tenant, version, mentionId, "entity-v2", "v2"));
        var edges = resolutionEdges(tenant, mentionId);
        assertThat(edges).hasSize(2);
        assertThat(edges.stream().filter(edge -> Boolean.TRUE.equals(edge.get("active"))).toList())
                .singleElement().satisfies(edge -> assertThat(edge.get("target")).isEqualTo("entity-v2"));
        assertThat(edges.stream().filter(edge -> "SUPERSEDED".equals(edge.get("status"))).toList())
                .singleElement().satisfies(edge -> {
                    assertThat(edge.get("target")).isEqualTo("entity-v1");
                    assertThat(edge.get("superseded_at")).isNotNull();
                });
        assertThat(connector.getActiveResolutions(tenant, List.of(mentionId)))
                .singleElement().satisfies(active -> {
                    assertThat(active.canonicalEntityId()).isEqualTo("entity-v2");
                    assertThat(active.canonicalEntityType()).isEqualTo("MaintenanceSystem");
                });

        // Replay the v2 decision: no new edge, still one active resolution.
        connector.upsertResolution(decision(tenant, version, mentionId, "entity-v2", "v2"));
        assertThat(resolutionEdges(tenant, mentionId)).hasSize(2);
    }

    @Test
    void extractedRelationLookupReturnsProvenanceAndIsTenantScoped() {
        String tenant = TENANT + "-extracted-read";
        String version = "version-extracted-read";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        connector.upsertMentions(List.of(
                new ExtractedMention("subject-read", tenant, version, "ref", "ref:0",
                        0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", now),
                new ExtractedMention("object-read", tenant, version, "ref", "ref:0",
                        20, 30, 20, 30, "Protocol-A", "Protocol", now)));
        connector.upsertRelations(List.of(new ExtractedRelation("relation-read", tenant, version,
                "requires", "subject-read", "object-read", "ref:0", 0, 30, 0, 30, now)));

        assertThat(connector.getExtractedRelation(tenant, "relation-read")).get().satisfies(view -> {
            assertThat(view.subjectMentionId()).isEqualTo("subject-read");
            assertThat(view.objectMentionId()).isEqualTo("object-read");
            assertThat(view.predicate()).isEqualTo("requires");
            assertThat(view.sourceVersionId()).isEqualTo(version);
        });
        assertThat(connector.getExtractedRelation(TENANT + "-other", "relation-read")).isEmpty();
    }

    @Test
    void resolutionFailsClosedOnMissingEntityOrNonResolvedOutcome() {
        String tenant = TENANT + "-resolution-fail";
        String mentionId = "mention-resolution-fail";
        String version = "version-resolution-fail";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        connector.upsertMentions(List.of(new ExtractedMention(mentionId, tenant, version, "ref", "ref:0",
                0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", now)));
        cypher.write("CREATE (n:Entity {tenant: $tenant, id: 'entity-ok', label: 'Halcyon-7', "
                + "type: 'MaintenanceSystem'})", Map.of("tenant", tenant));

        connector.upsertResolution(new ResolutionDecision(tenant, version, mentionId,
                ResolutionOutcome.AMBIGUOUS, "LOW_MARGIN_AMBIGUOUS", null, null, 0.03, 0.029,
                "v1", "1.0.0", Map.of(), "RRF"));
        assertThat(resolutionEdges(tenant, mentionId)).isEmpty();

        assertThatThrownBy(() -> connector.upsertResolution(
                decision(tenant, version, mentionId, "entity-missing", "v1")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(resolutionEdges(tenant, mentionId)).isEmpty();

        cypher.write("CREATE (n:Entity {tenant: 'other-tenant', id: 'entity-other', "
                + "label: 'Halcyon-7', type: 'MaintenanceSystem'})", Map.of());
        assertThatThrownBy(() -> connector.upsertResolution(
                decision(tenant, version, mentionId, "entity-other", "v1")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(resolutionEdges(tenant, mentionId)).isEmpty();
    }

    private static ResolutionDecision decision(String tenant, String version, String mentionId,
                                               String entityId, String policyVersion) {
        return new ResolutionDecision(tenant, version, mentionId, ResolutionOutcome.RESOLVED,
                "RESOLVED_LABEL", entityId, "Halcyon-7", 0.0328, 0.0161, policyVersion,
                "1.0.0", Map.of(), "RRF");
    }

    private List<Map<String, Object>> resolutionEdges(String tenant, String mentionId) {
        return cypher.read("MATCH (m:ExtractedMention {tenant_id: $tenant, mention_id: $mentionId})"
                + "-[r:RESOLVES_TO]->(e:Entity) RETURN r.active AS active, r.status AS status, "
                + "r.superseded_at AS superseded_at, e.id AS target, r.policy_version AS policy, "
                + "r.retrieval_strategy AS retrieval, r.resolution_id AS resolution_id",
                Map.of("tenant", tenant, "mentionId", mentionId));
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
