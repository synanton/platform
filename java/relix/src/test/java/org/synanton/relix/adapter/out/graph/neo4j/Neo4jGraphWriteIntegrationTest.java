package org.synanton.relix.adapter.out.graph.neo4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.jgrapht.graph.DirectedMultigraph;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.ExtractedRelation;
import org.synanton.relix.index.EntityIndex;
import org.synanton.relix.graph.GraphEdge;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.GraphNode;
import org.synanton.relix.graph.PromotionIds;
import org.synanton.relix.graph.ResolutionIds;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.PromotionResult;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.relix.reconciliation.ResolutionKey;
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
        assertThat(count("MATCH ()-[e:REL {tenant_id: $tenant}]->() RETURN count(e) AS c")).isZero();

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
    void exactResolutionStateExposesIdentityEntityAndFenceForReconciliation() {
        String tenant = TENANT + "-reconcile-read";
        String version = "version-reconcile-read";
        String mentionId = "mention-reconcile-read";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        connector.upsertMentions(List.of(new ExtractedMention(mentionId, tenant, version, "ref", "ref:0",
                0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", now)));
        cypher.write("CREATE (n:Entity {tenant: $tenant, id: 'entity-reconcile', "
                + "label: 'Halcyon-7', type: 'MaintenanceSystem'})", Map.of("tenant", tenant));
        ResolutionDecision decision = decision(tenant, version, mentionId, "entity-reconcile", "v1", 7);

        connector.upsertResolution(decision);

        ResolutionKey key = new ResolutionKey(tenant, mentionId);
        assertThat(connector.activeResolution(key)).satisfies(observation -> {
            var state = observation.orElseThrow();
            assertThat(state.sourceVersionId()).isEqualTo(version);
            assertThat(state.resolutionId()).isEqualTo(ResolutionIds.of(decision));
            assertThat(state.entityId()).isEqualTo("entity-reconcile");
            assertThat(state.fencingToken()).isEqualTo(7);
            assertThat(state.policyVersion()).isEqualTo("v1");
            assertThat(state.retrievalStrategy()).isEqualTo("RRF");
        });
        assertThat(connector.hasResolutionHistory(key)).isTrue();
        ResolutionKey otherTenant = new ResolutionKey(tenant + "-other", mentionId);
        assertThat(connector.activeResolution(otherTenant)).isEmpty();
        assertThat(connector.hasResolutionHistory(otherTenant)).isFalse();
    }

    @Test
    void activeResolutionReadSurfacesPreExistingMultipleActiveCorruption() {
        String tenant = TENANT + "-reconcile-corrupt";
        String version = "version-reconcile-corrupt";
        String mentionId = "mention-reconcile-corrupt";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        connector.upsertMentions(List.of(new ExtractedMention(mentionId, tenant, version, "ref", "ref:0",
                0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", now)));
        cypher.write("CREATE (e:Entity {tenant: $tenant, id: 'corrupt-e1', label: 'Halcyon-7', "
                + "type: 'MaintenanceSystem'}) CREATE (f:Entity {tenant: $tenant, id: 'corrupt-e2', "
                + "label: 'Halcyon-7', type: 'MaintenanceSystem'})", Map.of("tenant", tenant));
        cypher.write("""
                MATCH (m:ExtractedMention {tenant_id: $tenant, mention_id: $mention})
                MATCH (e:Entity {tenant: $tenant, id: 'corrupt-e1'})
                MATCH (f:Entity {tenant: $tenant, id: 'corrupt-e2'})
                CREATE (m)-[:RESOLVES_TO {tenant_id: $tenant, mention_id: $mention,
                    source_version_id: $version, resolution_id: 'corrupt-r1', active: true,
                    fencing_token: 1}]->(e)
                CREATE (m)-[:RESOLVES_TO {tenant_id: $tenant, mention_id: $mention,
                    source_version_id: $version, resolution_id: 'corrupt-r2', active: true,
                    fencing_token: 1}]->(f)
                """, Map.of("tenant", tenant, "mention", mentionId, "version", version));

        var observation = connector.activeResolution(new ResolutionKey(tenant, mentionId));
        assertThat(observation).isPresent();
        assertThat(observation.orElseThrow().multipleActive()).isTrue();
        assertThat(connector.hasResolutionHistory(new ResolutionKey(tenant, mentionId))).isTrue();
    }

    @Test
    void entityNeighborhoodReturnsBoundedOneHopRelationsAndEvidence() {
        String tenant = TENANT + "-neighbors";
        cypher.write("""
                CREATE (c:Entity {tenant: $tenant, id: 'center', label: 'Center', type: 'Concept'})
                CREATE (a:Entity {tenant: $tenant, id: 'alpha', label: 'Alpha', type: 'Concept'})
                CREATE (b:Entity {tenant: $tenant, id: 'beta', label: 'Beta', type: 'Concept'})
                CREATE (c)-[:REL {tenant_id: $tenant, canonical_relation_id: 'rel-out',
                    predicate: 'points_to'}]->(a)
                CREATE (a)-[:REL {tenant_id: $tenant, canonical_relation_id: 'rel-back',
                    predicate: 'observed_by'}]->(c)
                CREATE (b)-[:REL {tenant_id: $tenant, canonical_relation_id: 'rel-in',
                    predicate: 'supports'}]->(c)
                CREATE (ev1:RelationEvidence {tenant_id: $tenant,
                    canonical_relation_id: 'rel-out', evidence_id: 'ev-neighbor',
                    evidence_chunk_id: 'chunk-1', source_version_id: 'version-1',
                    evidence_offset_start: 4, evidence_offset_end: 12})
                CREATE (ev2:RelationEvidence {tenant_id: $tenant,
                    canonical_relation_id: 'rel-in', evidence_id: 'ev-neighbor-in',
                    evidence_chunk_id: 'chunk-2', source_version_id: 'version-2',
                    evidence_offset_start: 20, evidence_offset_end: 28})
                CREATE (x:Entity {tenant: 'other-tenant', id: 'other-alpha',
                    label: 'Other', type: 'Concept'})
                CREATE (c)-[:REL {tenant_id: $tenant, canonical_relation_id: 'rel-cross',
                    predicate: 'leaks'}]->(x)
                """, Map.of("tenant", tenant));

        var neighborhood = connector.neighborhood(tenant, "center", 50).orElseThrow();

        assertThat(neighborhood.entityId()).isEqualTo("center");
        assertThat(neighborhood.neighbors()).hasSize(3);
        assertThat(neighborhood.neighbors()).noneMatch(neighbor ->
                "other-alpha".equals(neighbor.entityId()));
        assertThat(neighborhood.neighbors().stream()
                .filter(neighbor -> "alpha".equals(neighbor.entityId()))
                .map(org.synanton.relix.graph.EntityNeighborhood.Neighbor::direction)
                .toList()).containsExactlyInAnyOrder("OUTGOING", "INCOMING");
        assertThat(neighborhood.neighbors()).anySatisfy(neighbor -> {
            if ("OUTGOING".equals(neighbor.direction()) && "alpha".equals(neighbor.entityId())) {
                assertThat(neighbor.relation()).isEqualTo("points_to");
                assertThat(neighbor.canonicalRelationId()).isEqualTo("rel-out");
            }
        });
        assertThat(neighborhood.evidence()).hasSize(2).allSatisfy(evidence -> {
            if ("points_to".equals(evidence.relation())) {
                assertThat(evidence.canonicalRelationId()).isEqualTo("rel-out");
                assertThat(evidence.neighborEntityId()).isEqualTo("alpha");
                assertThat(evidence.chunkId()).isEqualTo("chunk-1");
                assertThat(evidence.sourceVersion()).isEqualTo("version-1");
                assertThat(evidence.offsetStart()).isEqualTo(4);
                assertThat(evidence.offsetEnd()).isEqualTo(12);
            } else {
                assertThat(evidence.relation()).isEqualTo("supports");
                assertThat(evidence.canonicalRelationId()).isEqualTo("rel-in");
                assertThat(evidence.neighborEntityId()).isEqualTo("beta");
                assertThat(evidence.chunkId()).isEqualTo("chunk-2");
                assertThat(evidence.sourceVersion()).isEqualTo("version-2");
                assertThat(evidence.offsetStart()).isEqualTo(20);
                assertThat(evidence.offsetEnd()).isEqualTo(28);
            }
        });

        var bounded = connector.neighborhood(tenant, "center", 1).orElseThrow();
        assertThat(bounded.neighbors()).hasSize(1);
        assertThat(bounded.truncated()).isTrue();
        assertThat(connector.neighborhood(tenant, "missing", 50)).isEmpty();
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

    private List<Map<String, Object>> resolutionEdges(String tenant, String mentionId) {
        return cypher.read("MATCH (m:ExtractedMention {tenant_id: $tenant, mention_id: $mentionId})"
                + "-[r:RESOLVES_TO]->(e:Entity) RETURN r.active AS active, r.status AS status, "
                + "r.superseded_at AS superseded_at, e.id AS target, r.policy_version AS policy, "
                + "r.retrieval_strategy AS retrieval, r.resolution_id AS resolution_id",
                Map.of("tenant", tenant, "mentionId", mentionId));
    }

    @Test
    void promotionCreatesOneSemanticRelationAndAccumulatesEvidence() {
        String tenant = TENANT + "-promotion";
        String version = "version-promotion";
        String version2 = "version-promotion-2";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        seedPromotionEndpoint(tenant, version, "s1", "o1", "e-s", "e-o", now);
        seedPromotionEndpoint(tenant, version2, "s2", "o2", "e-s", "e-o", now);

        CanonicalRelation firstAssertion = new CanonicalRelation(tenant, version, "relation-p1",
                "s1", "e-s", "o1", "e-o", "requires", "1.0.0", "ref:0", 0, 20);
        assertThat(connector.promoteRelation(firstAssertion)).isEqualTo(new PromotionResult(1, 1, 1));
        assertThat(promotionCounts(tenant)).containsEntry("relations", 1L)
                .containsEntry("evidence", 1L).containsEntry("supports", 1L);
        assertThat(cypher.read("MATCH ()-[r:REL {tenant_id: $tenant}]->() "
                + "RETURN r.canonical_relation_id AS id", Map.of("tenant", tenant)).getFirst().get("id"))
                .isEqualTo(PromotionIds.canonicalRelationId(firstAssertion));

        // Replay is idempotent.
        assertThat(connector.promoteRelation(firstAssertion)).isEqualTo(new PromotionResult(0, 0, 1));
        assertThat(promotionCounts(tenant)).containsEntry("relations", 1L)
                .containsEntry("evidence", 1L).containsEntry("supports", 1L);

        // A second document asserting the same canonical triple adds evidence, not a second REL.
        CanonicalRelation secondAssertion = new CanonicalRelation(tenant, version2, "relation-p2",
                "s2", "e-s", "o2", "e-o", "requires", "1.0.0", "ref:1", 0, 20);
        assertThat(connector.promoteRelation(secondAssertion)).isEqualTo(new PromotionResult(0, 1, 1));
        assertThat(promotionCounts(tenant)).containsEntry("relations", 1L)
                .containsEntry("evidence", 2L).containsEntry("supports", 2L);
    }

    @Test
    void promotionFailsClosedWithoutActiveResolutionsOrForAnotherTenant() {
        String tenant = TENANT + "-promotion-fail";
        String version = "version-promotion-fail";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        connector.upsertMentions(List.of(
                new ExtractedMention("s-no-resolution", tenant, version, "ref", "ref:0",
                        0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", now),
                new ExtractedMention("o-no-resolution", tenant, version, "ref", "ref:0",
                        20, 30, 20, 30, "Protocol-A", "Protocol", now)));
        cypher.write("CREATE (n:Entity {tenant: $tenant, id: 'e-s-fail', label: 'Halcyon-7', "
                + "type: 'MaintenanceSystem'})", Map.of("tenant", tenant));
        cypher.write("CREATE (n:Entity {tenant: $tenant, id: 'e-o-fail', label: 'Protocol-A', "
                + "type: 'Protocol'})", Map.of("tenant", tenant));

        CanonicalRelation missingResolution = new CanonicalRelation(tenant, version, "relation-fail",
                "s-no-resolution", "e-s-fail", "o-no-resolution", "e-o-fail", "requires",
                "1.0.0", "ref:0", 0, 20);
        assertThat(connector.promoteRelation(missingResolution)).isEqualTo(new PromotionResult(0, 0, 0));
        assertThat(promotionCounts(tenant)).containsEntry("relations", 0L)
                .containsEntry("evidence", 0L).containsEntry("supports", 0L);

        CanonicalRelation crossTenant = new CanonicalRelation(TENANT + "-other-tenant", version,
                "relation-fail", "s-no-resolution", "e-s-fail", "o-no-resolution", "e-o-fail",
                "requires", "1.0.0", "ref:0", 0, 20);
        assertThat(connector.promoteRelation(crossTenant)).isEqualTo(new PromotionResult(0, 0, 0));
    }

    private void seedPromotionEndpoint(String tenant, String version, String subjectMention,
                                       String objectMention, String subjectEntity,
                                       String objectEntity, Instant now) {
        connector.upsertMentions(List.of(
                new ExtractedMention(subjectMention, tenant, version, "ref", "ref:0",
                        0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", now),
                new ExtractedMention(objectMention, tenant, version, "ref", "ref:0",
                        20, 30, 20, 30, "Protocol-A", "Protocol", now)));
        cypher.write("MERGE (n:Entity {tenant: $tenant, id: $id}) SET n.label = $label, n.type = $type",
                Map.of("tenant", tenant, "id", subjectEntity, "label", "Halcyon-7",
                        "type", "MaintenanceSystem"));
        cypher.write("MERGE (n:Entity {tenant: $tenant, id: $id}) SET n.label = $label, n.type = $type",
                Map.of("tenant", tenant, "id", objectEntity, "label", "Protocol-A",
                        "type", "Protocol"));
        connector.upsertResolution(new ResolutionDecision(tenant, version, subjectMention,
                org.synanton.common.resolution.ResolutionOutcome.RESOLVED, "RESOLVED_LABEL",
                subjectEntity, "Halcyon-7", 0.03, 0.01, "v1", "1.0.0", Map.of(), "RRF"));
        connector.upsertResolution(new ResolutionDecision(tenant, version, objectMention,
                org.synanton.common.resolution.ResolutionOutcome.RESOLVED, "RESOLVED_LABEL",
                objectEntity, "Protocol-A", 0.03, 0.01, "v1", "1.0.0", Map.of(), "RRF"));
    }

    private Map<String, Long> promotionCounts(String tenant) {
        return Map.of(
                "relations", countForTenant("MATCH ()-[r:REL {tenant_id: $tenant}]->() "
                        + "RETURN count(r) AS c", tenant),
                "evidence", countForTenant("MATCH (ev:RelationEvidence {tenant_id: $tenant}) "
                        + "RETURN count(ev) AS c", tenant),
                "supports", countForTenant("MATCH (:ExtractedRelation {tenant_id: $tenant})"
                        + "-[:SUPPORTS]->(:RelationEvidence {tenant_id: $tenant}) RETURN count(*) AS c", tenant));
    }

    private long countForTenant(String query, String tenant) {
        Object value = cypher.read(query, Map.of("tenant", tenant)).getFirst().get("c");
        return ((Number) value).longValue();
    }

    @Test
    void concurrentIdenticalResolutionsLeaveOneActiveEdge() throws Exception {
        String tenant = TENANT + "-concurrent-resolve";
        String version = "version-concurrent-resolve";
        String mention = "mention-concurrent-resolve";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        connector.upsertMentions(List.of(new ExtractedMention(mention, tenant, version, "ref", "ref:0",
                0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", now)));
        cypher.write("MERGE (n:Entity {tenant: $tenant, id: 'e-concurrent'}) "
                + "SET n.label = 'Halcyon-7', n.type = 'MaintenanceSystem'", Map.of("tenant", tenant));
        ResolutionDecision decision = decision(tenant, version, mention, "e-concurrent", "v1");

        runConcurrently(10, ignored -> () -> connector.upsertResolution(decision));

        var edges = resolutionEdges(tenant, mention);
        assertThat(edges).hasSize(1);
        assertThat(edges).allSatisfy(edge -> assertThat(edge.get("active")).isEqualTo(true));
    }

    @Test
    void concurrentDistinctResolutionsLeaveOneActiveAndNineSuperseded() throws Exception {
        String tenant = TENANT + "-concurrent-supersede";
        String version = "version-concurrent-supersede";
        String mention = "mention-concurrent-supersede";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        connector.upsertMentions(List.of(new ExtractedMention(mention, tenant, version, "ref", "ref:0",
                0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", now)));
        cypher.write("MERGE (n:Entity {tenant: $tenant, id: 'e-concurrent-s'}) "
                + "SET n.label = 'Halcyon-7', n.type = 'MaintenanceSystem'", Map.of("tenant", tenant));
        cypher.write("MERGE (n:Entity {tenant: $tenant, id: 'e-concurrent-o'}) "
                + "SET n.label = 'Halcyon-7', n.type = 'MaintenanceSystem'", Map.of("tenant", tenant));

        runConcurrently(10, index -> () -> {
            String entity = index % 2 == 0 ? "e-concurrent-s" : "e-concurrent-o";
            connector.upsertResolution(decision(tenant, version, mention, entity, "v" + index));
        });

        var edges = resolutionEdges(tenant, mention);
        assertThat(edges).hasSize(10);
        assertThat(edges.stream().filter(edge -> Boolean.TRUE.equals(edge.get("active"))).toList()).hasSize(1);
        assertThat(edges.stream().filter(edge -> "SUPERSEDED".equals(edge.get("status"))).toList()).hasSize(9);
    }

    @Test
    void staleFencingTokenCannotOverwriteANewerDecision() {
        String tenant = TENANT + "-fencing";
        String version = "version-fencing";
        String mention = "mention-fencing";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        connector.upsertMentions(List.of(new ExtractedMention(mention, tenant, version, "ref", "ref:0",
                0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", now)));
        cypher.write("MERGE (n:Entity {tenant: $tenant, id: 'e-fencing'}) "
                + "SET n.label = 'Halcyon-7', n.type = 'MaintenanceSystem'", Map.of("tenant", tenant));

        connector.upsertResolution(decision(tenant, version, mention, "e-fencing", "v2", 5));
        assertThatThrownBy(() -> connector.upsertResolution(
                decision(tenant, version, mention, "e-fencing", "v1", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stale");

        var edges = resolutionEdges(tenant, mention);
        assertThat(edges).hasSize(1);
        assertThat(edges.getFirst().get("active")).isEqualTo(true);
    }

    @Test
    void concurrentPromotionOfSameAssertionWritesOnce() throws Exception {
        String tenant = TENANT + "-concurrent-promote";
        String version = "version-concurrent-promote";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        seedPromotionEndpoint(tenant, version, "s-c", "o-c", "e-s-c", "e-o-c", now);
        CanonicalRelation relation = new CanonicalRelation(tenant, version, "relation-c",
                "s-c", "e-s-c", "o-c", "e-o-c", "requires", "1.0.0", "ref:0", 0, 20);

        runConcurrently(10, ignored -> () -> connector.promoteRelation(relation));

        assertThat(promotionCounts(tenant)).containsEntry("relations", 1L)
                .containsEntry("evidence", 1L).containsEntry("supports", 1L);
    }

    @Test
    void concurrentPromotionOfSameTripleAccumulatesTenEvidenceNodes() throws Exception {
        String tenant = TENANT + "-concurrent-evidence";
        String version = "version-concurrent-evidence";
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        seedPromotionEndpoint(tenant, version, "s-e", "o-e", "e-s-e", "e-o-e", now);

        runConcurrently(10, index -> () -> connector.promoteRelation(new CanonicalRelation(
                tenant, version, "relation-e" + index, "s-e", "e-s-e", "o-e", "e-o-e",
                "requires", "1.0.0", "ref:" + index, 0, 20)));

        assertThat(promotionCounts(tenant)).containsEntry("relations", 1L)
                .containsEntry("evidence", 10L).containsEntry("supports", 10L);
    }

    private static void runConcurrently(int threads, IntFunction<Runnable> taskFactory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                taskFactory.apply(index).run();
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }

    private static ResolutionDecision decision(String tenant, String version, String mentionId,
                                               String entityId, String policyVersion) {
        return decision(tenant, version, mentionId, entityId, policyVersion, 0);
    }

    private static ResolutionDecision decision(String tenant, String version, String mentionId,
                                               String entityId, String policyVersion, long fencingToken) {
        return new ResolutionDecision(tenant, version, mentionId, ResolutionOutcome.RESOLVED,
                "RESOLVED_LABEL", entityId, "Halcyon-7", 0.0328, 0.0161, policyVersion,
                "1.0.0", Map.of(), "RRF", fencingToken);
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

    @Test
    void repeatedTenantLoadDoesNotDeleteCanonicalGraph() {
        String tenant = TENANT + "-load-repeat";
        seedCanonicalGraph(tenant);
        GraphLoader loader = mock(GraphLoader.class);
        when(loader.load(tenant)).thenReturn(emptyLoadResult());
        Neo4jGraphConnector local = new Neo4jGraphConnector(loader, cypher);

        local.loadTenant(tenant);
        local.loadTenant(tenant);

        assertThat(countForTenant("MATCH (n:Entity {tenant: $tenant}) RETURN count(n) AS c", tenant))
                .isEqualTo(2);
        assertThat(countForTenant("MATCH ()-[r:REL {tenant_id: $tenant}]->() RETURN count(r) AS c", tenant))
                .isEqualTo(1);
    }

    @Test
    void partialTenantSnapshotDoesNotDeleteExistingCanonicalGraph() {
        String tenant = TENANT + "-load-partial";
        seedCanonicalGraph(tenant);
        GraphLoader loader = mock(GraphLoader.class);
        GraphNode onlyNode = new GraphNode(java.util.UUID.randomUUID(), "Only", "Concept", 0.7);
        when(loader.load(tenant)).thenReturn(loadResult(onlyNode, null, null));
        Neo4jGraphConnector local = new Neo4jGraphConnector(loader, cypher);

        local.loadTenant(tenant);

        assertThat(countForTenant("MATCH (n:Entity {tenant: $tenant}) RETURN count(n) AS c", tenant))
                .isEqualTo(3);
        assertThat(countForTenant("MATCH ()-[r:REL {tenant_id: $tenant}]->() RETURN count(r) AS c", tenant))
                .isEqualTo(1);
    }

    @Test
    void interruptedTenantLoadLeavesExistingCanonicalGraphIntact() {
        String tenant = TENANT + "-load-interrupted";
        seedCanonicalGraph(tenant);
        GraphLoader loader = mock(GraphLoader.class);
        GraphNode from = new GraphNode(java.util.UUID.randomUUID(), "From", "Concept", 0.7);
        GraphNode to = new GraphNode(java.util.UUID.randomUUID(), "To", "Concept", 0.7);
        when(loader.load(tenant)).thenReturn(loadResult(from, to, "requires"));
        CypherExecutor failingOnEdgeWrite = new CypherExecutor() {
            @Override
            public List<Map<String, Object>> read(String query, Map<String, Object> params) {
                return cypher.read(query, params);
            }

            @Override
            public void write(String query, Map<String, Object> params) {
                if (query.contains("UNWIND $edges")) {
                    throw new IllegalStateException("injected reload interruption");
                }
                cypher.write(query, params);
            }
        };
        Neo4jGraphConnector local = new Neo4jGraphConnector(loader, failingOnEdgeWrite);

        assertThatThrownBy(() -> local.loadTenant(tenant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected reload interruption");

        assertThat(countForTenant("MATCH (n:Entity {tenant: $tenant}) RETURN count(n) AS c", tenant))
                .isEqualTo(2);
        assertThat(countForTenant("MATCH ()-[r:REL {tenant_id: $tenant}]->() RETURN count(r) AS c", tenant))
                .isEqualTo(1);
    }

    private void seedCanonicalGraph(String tenant) {
        cypher.write("""
                CREATE (a:Entity {tenant: $tenant, id: 'existing-a', label: 'Existing A', type: 'Concept'})
                CREATE (b:Entity {tenant: $tenant, id: 'existing-b', label: 'Existing B', type: 'Concept'})
                CREATE (a)-[r:REL {tenant_id: $tenant, id: 'existing-rel'}]->(b)
                SET r.verb = 'relates_to'
                """, Map.of("tenant", tenant));
    }

    private static GraphLoader.LoadResult emptyLoadResult() {
        DirectedMultigraph<GraphNode, GraphEdge> graph = new DirectedMultigraph<>(GraphEdge.class);
        return new GraphLoader.LoadResult(graph, new EntityIndex(), Map.of(), 0, 1);
    }

    private static GraphLoader.LoadResult loadResult(GraphNode from, GraphNode to, String verb) {
        DirectedMultigraph<GraphNode, GraphEdge> graph = new DirectedMultigraph<>(GraphEdge.class);
        Map<java.util.UUID, GraphNode> nodes = new java.util.LinkedHashMap<>();
        graph.addVertex(from);
        nodes.put(from.entityId(), from);
        if (to != null) {
            graph.addVertex(to);
            nodes.put(to.entityId(), to);
            graph.addEdge(from, to, new GraphEdge(java.util.UUID.randomUUID(), verb, 0.8));
        }
        return new GraphLoader.LoadResult(graph, new EntityIndex(), nodes, 0, 1);
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

        @Override
        public List<Map<String, Object>> writeReturning(String cypher, Map<String, Object> params) {
            try (Session session = driver.session()) {
                return session.executeWrite(tx -> {
                    var result = tx.run(cypher, params);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (result.hasNext()) {
                        rows.add(result.next().asMap());
                    }
                    return rows;
                });
            }
        }
    }
}
