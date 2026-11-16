package org.synanton.relix.integration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.resolution.PromotionResult;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.relix.adapter.out.graph.neo4j.DriverCypherExecutor;
import org.synanton.relix.adapter.out.graph.neo4j.Neo4jGraphConnector;
import org.synanton.relix.graph.PromotionIds;
import org.synanton.relix.graph.RelixCanonicalRelationPromoter;
import org.synanton.relix.identity.GraphIdentityResolver;
import org.synanton.relix.identity.SourceGenerationAuthority;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void conflictingSourceProducesContradictingEvidenceThroughRealFlow() {
        String tenant = "tenant-655-real-conflict";
        String subject = "subject";
        String object = "object";
        String predicate = "requires";
        UUID v2 = UUID.randomUUID();
        CanonicalRelation v2Relation = new CanonicalRelation(
                tenant, v2.toString(), "relation-v2", "mention-v2-subject", subject,
                "mention-v2-object", object, predicate, "active", "chunk-v2:0", 0, 5,
                "source-v2", v2.toString(), 1, "run-v2");
        String canonicalRelationId = PromotionIds.canonicalRelationId(v2Relation);
        seed(tenant, canonicalRelationId, "ev-v1-support", "source-v1", "v1", 1,
                "SUPPORTS", "CURRENT");

        String evidenceJson = "{\"view\":{\"evidenceChunkId\":\"chunk-v2:0\","
                + "\"evidenceChunkOffsetStart\":0,\"evidenceChunkOffsetEnd\":5}}";
        RelationPromotionResultRow invalidRow = new RelationPromotionResultRow(
                tenant, v2, "relation-v2", "mention-v2-subject", "mention-v2-object",
                predicate, "INVALID", subject, object,
                "ONTOLOGY_INVALID:domain_mismatch", evidenceJson,
                "relation-validation-v1", "active", Instant.now(), null,
                "source-v2", v2, 1, "run-v2", "CURRENT");
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        when(cache.listRelationPromotionResults(tenant, v2)).thenReturn(List.of(invalidRow));
        GraphIdentityResolver resolver = tenantId -> GraphIdentity.canonical(tenantId, "v1");
        SourceGenerationAuthority authority = key -> Optional.of(1L);
        RelixCanonicalRelationPromoter promoter = new RelixCanonicalRelationPromoter(
                cache, graph, resolver, authority);

        assertThat(promoter.promoteRelation(v2Relation)).isEqualTo(new PromotionResult(0, 1, 0));

        List<Map<String, Object>> contradiction = cypher.read("""
                MATCH (e:RelationEvidence {tenant_id: $tenant, source_version_id: $version})
                RETURN e.polarity AS polarity, e.status AS status,
                       e.canonical_relation_id AS canonical_relation_id
                """, Map.of("tenant", tenant, "version", v2.toString()));
        assertThat(contradiction).singleElement().satisfies(row -> {
            assertThat(row.get("polarity")).isEqualTo("CONTRADICTS");
            assertThat(row.get("status")).isEqualTo("CONTRADICTING");
            assertThat(row.get("canonical_relation_id")).isEqualTo(canonicalRelationId);
        });
        Map<String, Object> rel = relationState(tenant, canonicalRelationId);
        assertThat(rel.get("status")).isEqualTo("CONFLICTED");
        assertThat(rel.get("active")).isEqualTo(false);
        assertThat(rel.get("review_required")).isEqualTo(true);
        assertThat(cypher.read("""
                MATCH (e:RelationEvidence {tenant_id: $tenant, canonical_relation_id: $relation})
                RETURN count(e) AS c
                """, Map.of("tenant", tenant, "relation", canonicalRelationId))
                .getFirst().get("c")).isEqualTo(2L);
    }

    @Test
    void failedAssertionDoesNotWriteContradictingEvidence() {
        String tenant = "tenant-713-failed";
        String subject = "subject";
        String object = "object";
        String predicate = "requires";
        UUID v2 = UUID.randomUUID();
        CanonicalRelation v2Relation = new CanonicalRelation(
                tenant, v2.toString(), "relation-713-failed", "mention-713-subject", subject,
                "mention-713-object", object, predicate, "active", "chunk-713:0", 0, 5,
                "source-713-v2", v2.toString(), 1, "run-713");
        String canonicalRelationId = PromotionIds.canonicalRelationId(v2Relation);
        seed(tenant, canonicalRelationId, "ev-713-v1-support", "source-713-v1", "v1", 1,
                "SUPPORTS", "CURRENT");

        String evidenceJson = "{\"view\":{\"evidenceChunkId\":\"chunk-713:0\","
                + "\"evidenceChunkOffsetStart\":0,\"evidenceChunkOffsetEnd\":5}}";
        RelationPromotionResultRow failedRow = new RelationPromotionResultRow(
                tenant, v2, "relation-713-failed", "mention-713-subject", "mention-713-object",
                predicate, "INVALID", subject, object,
                "EXTRACTED_EVIDENCE_NOT_FOUND:EXTRACTED_EVIDENCE_NOT_FOUND", evidenceJson,
                "relation-validation-v1", "active", Instant.now(), null,
                "source-713-v2", v2, 1, "run-713", "CURRENT");
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        when(cache.listRelationPromotionResults(tenant, v2)).thenReturn(List.of(failedRow));
        GraphIdentityResolver resolver = tenantId -> GraphIdentity.canonical(tenantId, "v1");
        SourceGenerationAuthority authority = key -> Optional.of(1L);
        RelixCanonicalRelationPromoter promoter = new RelixCanonicalRelationPromoter(
                cache, graph, resolver, authority);

        assertThat(promoter.promoteRelation(v2Relation)).isEqualTo(new PromotionResult(0, 0, 0));

        assertThat(cypher.read("""
                MATCH (e:RelationEvidence {tenant_id: $tenant, source_version_id: $version})
                RETURN count(e) AS c
                """, Map.of("tenant", tenant, "version", v2.toString()))
                .getFirst().get("c")).isEqualTo(0L);
        Map<String, Object> rel = relationState(tenant, canonicalRelationId);
        assertThat(rel.get("status")).isEqualTo("ACTIVE");
        assertThat(rel.get("active")).isEqualTo(true);
        assertThat(rel.get("review_required")).isEqualTo(false);
        assertThat(cypher.read("""
                MATCH (e:RelationEvidence {tenant_id: $tenant, canonical_relation_id: $relation})
                RETURN e.polarity AS polarity, e.status AS status
                """, Map.of("tenant", tenant, "relation", canonicalRelationId)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("polarity")).isEqualTo("SUPPORTS");
                    assertThat(row.get("status")).isEqualTo("CURRENT");
                });
    }

    @Test
    void driftRepairRestoresDerivedRelState() {
        String activeTenant = "tenant-655-drift-active";
        String activeRelation = "rel-655-drift-active";
        seed(activeTenant, activeRelation, "ev-drift-support", "source-a", "v1", 1,
                "SUPPORTS", "CURRENT");
        corruptRelation(activeTenant, activeRelation, "CONFLICTED", false, true);

        assertThat(graph.reconcileRetirementState(activeTenant, 500))
                .as("a corrupted materialized :REL must be repaired from its evidence")
                .isPositive();
        Map<String, Object> active = relationState(activeTenant, activeRelation);
        assertThat(active.get("status")).isEqualTo("ACTIVE");
        assertThat(active.get("active")).isEqualTo(true);
        assertThat(evidenceStatus(activeTenant, "ev-drift-support")).isEqualTo("CURRENT");
        assertThat(graph.reconcileRetirementState(activeTenant, 500))
                .as("the durable watermark makes the second sweep a no-op")
                .isZero();

        String conflictTenant = "tenant-655-drift-conflict";
        String conflictRelation = "rel-655-drift-conflict";
        seed(conflictTenant, conflictRelation, "ev-drift-conflict-support", "source-a", "v2", 2,
                "SUPPORTS", "CURRENT");
        seed(conflictTenant, conflictRelation, "ev-drift-contradict", "source-b", "v1", 1,
                "CONTRADICTS", "CONTRADICTING");
        graph.recomputeAssertionState(conflictTenant, conflictRelation);
        assertThat(relationState(conflictTenant, conflictRelation).get("status"))
                .isEqualTo("CONFLICTED");

        cypher.write("""
                MATCH (e:RelationEvidence {tenant_id: $tenant, evidence_id: $evidence})
                SET e.status = 'SUPERSEDED', e.status_changed_at = $now
                """, Map.of("tenant", conflictTenant, "evidence", "ev-drift-contradict",
                "now", Instant.now().toString()));
        assertThat(relationState(conflictTenant, conflictRelation).get("status"))
                .as("the crash left stale materialized state")
                .isEqualTo("CONFLICTED");
        assertThat(graph.reconcileRetirementState(conflictTenant, 500)).isPositive();
        Map<String, Object> conflict = relationState(conflictTenant, conflictRelation);
        assertThat(conflict.get("status")).isEqualTo("ACTIVE");
        assertThat(conflict.get("active")).isEqualTo(true);
        assertThat(evidenceStatus(conflictTenant, "ev-drift-contradict"))
                .as("the sweep derives state and never mutates evidence")
                .isEqualTo("SUPERSEDED");
        assertThat(graph.reconcileRetirementState(conflictTenant, 500)).isZero();

        String unsupportedTenant = "tenant-655-drift-unsupported";
        String unsupportedRelation = "rel-655-drift-unsupported";
        seed(unsupportedTenant, unsupportedRelation, "ev-drift-new-support", "source-c", "v4", 1,
                "SUPPORTS", "CURRENT");
        corruptRelation(unsupportedTenant, unsupportedRelation, "UNSUPPORTED", false, false);
        assertThat(graph.reconcileRetirementState(unsupportedTenant, 500)).isPositive();
        Map<String, Object> unsupported = relationState(unsupportedTenant, unsupportedRelation);
        assertThat(unsupported.get("status")).isEqualTo("ACTIVE");
        assertThat(unsupported.get("active")).isEqualTo(true);
    }

    private static void corruptRelation(String tenant, String relation, String status,
                                        boolean active, boolean review) {
        cypher.write("""
                MATCH ()-[r:REL {tenant_id: $tenant, canonical_relation_id: $relation}]->()
                SET r.status = $status, r.active = $active, r.review_required = $review
                """, Map.of("tenant", tenant, "relation", relation, "status", status,
                "active", active, "review", review));
    }

    private static void seed(String tenant, String relation, String evidence, String sourceId,
                             String versionId, long generation, String polarity, String status) {
        seed(tenant, relation, evidence, sourceId, versionId, generation, polarity, status,
                Instant.now().toString());
    }

    private static void seed(String tenant, String relation, String evidence, String sourceId,
                             String versionId, long generation, String polarity, String status,
                             String changedAt) {
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
                    e.status_changed_at = $changedAt,
                    e.evidence_chunk_id = $evidence + ':0'
                """, Map.of(
                "tenant", tenant,
                "relation", relation,
                "evidence", evidence,
                "sourceId", sourceId,
                "versionId", versionId,
                "generation", generation,
                "polarity", polarity,
                "status", status,
                "changedAt", changedAt));
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
