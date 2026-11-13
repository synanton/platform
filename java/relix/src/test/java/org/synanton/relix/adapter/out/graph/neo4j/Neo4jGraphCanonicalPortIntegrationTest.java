package org.synanton.relix.adapter.out.graph.neo4j;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.FenceBehindException;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.resolution.PromotionResult;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.common.resolution.SourceRevisionIdentity;
import org.synanton.common.resolution.StaleGenerationException;
import org.synanton.relix.identity.SourceFenceAdvance;
import org.synanton.relix.identity.SourceFenceKey;
import org.synanton.relix.api.dto.Edge;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.adapter.out.metadata.Neo4jSourceFenceStore;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.PromotionIds;
import org.synanton.relix.identity.UnsupportedGraphIdException;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class Neo4jGraphCanonicalPortIntegrationTest {

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
    void canonicalGraphIdIsAccepted() {
        String tenant = "tenant-canonical-accepted";
        UUID id = UUID.randomUUID();
        GraphIdentity identity = GraphIdentity.canonical(tenant, "v1");

        connector.upsertEntity(identity, entity(id, "Accepted", "Thing"));

        assertThat(connector.getEntity(identity, id.toString()))
                .get()
                .extracting(Entity::label)
                .isEqualTo("Accepted");
    }

    @Test
    void nonCanonicalGraphIdIsRejected() {
        GraphIdentity identity = new GraphIdentity("tenant-a", "annotations", "v1");

        assertThatThrownBy(() -> connector.getEntity(identity, UUID.randomUUID().toString()))
                .isInstanceOf(UnsupportedGraphIdException.class);
    }

    @Test
    void tenantIsolationReads() {
        String tenantA = "tenant-isolation-a";
        String tenantB = "tenant-isolation-b";
        UUID id = UUID.randomUUID();
        connector.upsertEntity(GraphIdentity.canonical(tenantA, "v1"),
                entity(id, "Tenant A", "Thing"));

        assertThat(connector.getEntity(GraphIdentity.canonical(tenantA, "v1"), id.toString()))
                .isPresent();
        assertThat(connector.getEntity(GraphIdentity.canonical(tenantB, "v1"), id.toString()))
                .isEmpty();
    }

    @Test
    void tenantIsolationWrites() {
        String tenantA = "tenant-write-a";
        String tenantB = "tenant-write-b";
        UUID id = UUID.randomUUID();
        connector.upsertEntity(GraphIdentity.canonical(tenantA, "v1"),
                entity(id, "Write A", "Thing"));

        assertThat(connector.lookupEntities(GraphIdentity.canonical(tenantA, "v1"),
                "Write A", "Thing", 10).entities()).hasSize(1);
        assertThat(connector.lookupEntities(GraphIdentity.canonical(tenantB, "v1"),
                "Write A", "Thing", 10).entities()).isEmpty();
    }

    @Test
    void projectionVersionIsCarriedButDoesNotAffectScope() {
        String tenant = "tenant-projection";
        UUID id = UUID.randomUUID();
        connector.upsertEntity(GraphIdentity.canonical(tenant, "v1"),
                entity(id, "Projection", "Thing"));

        assertThat(connector.getEntity(GraphIdentity.canonical(tenant, "v2"), id.toString()))
                .isPresent();
    }

    @Test
    void existingTenantDataIsAccessible() {
        String tenant = "tenant-existing";
        String id = UUID.randomUUID().toString();
        cypher.write("""
                CREATE (:Entity {tenant: $tenant, id: $id, label: 'Existing',
                                 type: 'Thing', confidence: 1.0, sources: ''})
                """, Map.of("tenant", tenant, "id", id));

        assertThat(connector.getEntity(GraphIdentity.canonical(tenant, "v1"), id))
                .get()
                .extracting(Entity::label)
                .isEqualTo("Existing");
    }

    @Test
    void crossTenantWritesAreIsolatedWithTheSameEntityId() {
        String tenantA = "tenant-same-id-a";
        String tenantB = "tenant-same-id-b";
        UUID id = UUID.randomUUID();
        connector.upsertEntity(GraphIdentity.canonical(tenantA, "v1"),
                entity(id, "Shared A", "Thing"));
        connector.upsertEntity(GraphIdentity.canonical(tenantB, "v1"),
                entity(id, "Shared B", "Thing"));

        assertThat(connector.getEntity(GraphIdentity.canonical(tenantA, "v1"), id.toString()))
                .get().extracting(Entity::label).isEqualTo("Shared A");
        assertThat(connector.getEntity(GraphIdentity.canonical(tenantB, "v1"), id.toString()))
                .get().extracting(Entity::label).isEqualTo("Shared B");
    }

    @Test
    void canonicalEntityWriteDoesNotCreateExtractedMentionOrRelation() {
        String tenant = "tenant-canonical-write-surface";
        UUID id = UUID.randomUUID();
        GraphIdentity identity = GraphIdentity.canonical(tenant, "v1");

        connector.upsertEntity(identity, entity(id, "Canonical Only", "Thing"));

        assertThat(count("MATCH (n:ExtractedMention {tenant_id: $tenant}) RETURN count(n) AS c", tenant))
                .isZero();
        assertThat(count("MATCH ()-[r:EXTRACTED_RELATION {tenant_id: $tenant}]->() RETURN count(r) AS c", tenant))
                .isZero();
    }

    @Test
    void canonicalRelationWriteUsesRelNotExtractedRelation() {
        String tenant = "tenant-canonical-relation";
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        GraphIdentity identity = GraphIdentity.canonical(tenant, "v1");
        connector.upsertEntity(identity, entity(from, "From", "Thing"));
        connector.upsertEntity(identity, entity(to, "To", "Thing"));

        connector.upsertRelation(identity, new Edge(UUID.randomUUID(), from, to,
                "relates_to", 1.0, List.of()));

        assertThat(connector.oneHop(identity, from, List.of("relates_to"), "OUT", 10).edges())
                .hasSize(1);
        assertThat(count("MATCH ()-[r:EXTRACTED_RELATION {tenant_id: $tenant}]->() RETURN count(r) AS c", tenant))
                .isZero();
    }

    @Test
    void guardedPromotionRejectsBehindAndStaleFenceWithoutMutation() {
        String tenant = "tenant-fence-guard";
        String sourceId = "source-fence-guard";
        seedResolution(tenant, "subject-guard", "entity-subject-guard");
        seedResolution(tenant, "object-guard", "entity-object-guard");
        GraphIdentity identity = GraphIdentity.canonical(tenant, "v1");
        CanonicalRelation relation = new CanonicalRelation(tenant, "revision-guard", "relation-guard",
                "subject-guard", "entity-subject-guard", "object-guard", "entity-object-guard",
                "requires", "active", "evidence-guard", 0, 10,
                sourceId, "revision-guard", 2, null);

        cypher.write("""
                MERGE (f:SourceRevisionFence {tenant_id: $tenant, source_id: $sourceId})
                SET f.accepted_generation = 1
                """, Map.of("tenant", tenant, "sourceId", sourceId));
        assertThatThrownBy(() -> connector.promoteRelation(identity, relation,
                new SourceRevisionIdentity(tenant, sourceId, 2)))
                .isInstanceOf(FenceBehindException.class);
        assertThat(relationEvidenceCount(tenant, "relation-guard")).isZero();

        cypher.write("""
                MATCH (f:SourceRevisionFence {tenant_id: $tenant, source_id: $sourceId})
                SET f.accepted_generation = 3
                """, Map.of("tenant", tenant, "sourceId", sourceId));
        assertThatThrownBy(() -> connector.promoteRelation(identity, relation,
                new SourceRevisionIdentity(tenant, sourceId, 2)))
                .isInstanceOf(StaleGenerationException.class);
        assertThat(relationEvidenceCount(tenant, "relation-guard")).isZero();
    }

    @Test
    void delayedGenerationTwoCannotCommitAfterGenerationThreeFenceAdvance() {
        String tenant = "tenant-generation-race";
        String sourceId = "source-generation-race";
        seedResolution(tenant, "subject-race", "entity-subject-race");
        seedResolution(tenant, "object-race", "entity-object-race");
        GraphIdentity identity = GraphIdentity.canonical(tenant, "v1");
        cypher.write("""
                MERGE (f:SourceRevisionFence {tenant_id: $tenant, source_id: $sourceId})
                SET f.accepted_generation = 2
                """, Map.of("tenant", tenant, "sourceId", sourceId));

        CanonicalRelation gen2 = relation(tenant, "relation-race-gen2", "revision-race-gen2",
                "subject-race", "entity-subject-race", "object-race", "entity-object-race",
                "evidence-race-gen2", sourceId, 2);
        connector.promoteRelation(identity, gen2, new SourceRevisionIdentity(tenant, sourceId, 2));
        assertThat(relationEvidenceCount(tenant, "relation-race-gen2")).isEqualTo(1);

        cypher.write("""
                MATCH (f:SourceRevisionFence {tenant_id: $tenant, source_id: $sourceId})
                SET f.accepted_generation = 3
                """, Map.of("tenant", tenant, "sourceId", sourceId));
        CanonicalRelation gen3 = relation(tenant, "relation-race-gen3", "revision-race-gen3",
                "subject-race", "entity-subject-race", "object-race", "entity-object-race",
                "evidence-race-gen3", sourceId, 3);
        connector.promoteRelation(identity, gen3, new SourceRevisionIdentity(tenant, sourceId, 3));
        assertThat(relationEvidenceCount(tenant, "relation-race-gen3")).isEqualTo(1);

        CanonicalRelation delayedGen2 = relation(tenant, "relation-race-gen2-late", "revision-race-gen2",
                "subject-race", "entity-subject-race", "object-race", "entity-object-race",
                "evidence-race-gen2-late", sourceId, 2);
        assertThatThrownBy(() -> connector.promoteRelation(identity, delayedGen2,
                new SourceRevisionIdentity(tenant, sourceId, 2)))
                .isInstanceOf(StaleGenerationException.class);

        assertThat(relationEvidenceCount(tenant, "relation-race-gen2-late")).isZero();
        assertThat(relationEvidenceCount(tenant, "relation-race-gen2")).isEqualTo(1);
        assertThat(relationEvidenceCount(tenant, "relation-race-gen3")).isEqualTo(1);
    }

    private static CanonicalRelation relation(String tenant, String relationId, String revisionId,
                                              String subjectMentionId, String subjectEntityId,
                                              String objectMentionId, String objectEntityId,
                                              String evidenceChunkId, String sourceId, long generation) {
        return new CanonicalRelation(tenant, revisionId, relationId, subjectMentionId, subjectEntityId,
                objectMentionId, objectEntityId, "requires", "active", evidenceChunkId, 0, 10,
                sourceId, revisionId, generation, null);
    }

    @Test
    void abortedPromotionAfterFenceAndStagingLeavesNoEvidenceAndRetryWritesOnce() {
        String tenant = "tenant-abort-recovery";
        String sourceId = "source-abort-recovery";
        seedResolution(tenant, "subject-abort", "entity-subject-abort");
        seedResolution(tenant, "object-abort", "entity-object-abort");
        cypher.write("""
                MERGE (f:SourceRevisionFence {tenant_id: $tenant, source_id: $sourceId})
                SET f.accepted_generation = 2
                """, Map.of("tenant", tenant, "sourceId", sourceId));
        GraphIdentity identity = GraphIdentity.canonical(tenant, "v1");
        CanonicalRelation relation = relation(tenant, "relation-abort", "revision-abort",
                "subject-abort", "entity-subject-abort", "object-abort", "entity-object-abort",
                "evidence-abort", sourceId, 2);
        String canonicalId = PromotionIds.canonicalRelationId(relation);
        String evidenceId = PromotionIds.evidenceId(relation);
        AtomicBoolean injected = new AtomicBoolean();
        Neo4jGraphConnector aborting = new Neo4jGraphConnector(mock(GraphLoader.class), cypher, () -> {
            if (injected.compareAndSet(false, true)) {
                throw new InjectedAbort();
            }
        });

        assertThatThrownBy(() -> aborting.promoteRelation(identity, relation,
                new SourceRevisionIdentity(tenant, sourceId, 2)))
                .isInstanceOf(RetryableProcessingException.class)
                .hasCauseInstanceOf(InjectedAbort.class);

        // Independent real-store observables before retry, not the exception alone.
        assertThat(promotionArtifacts(tenant, canonicalId, evidenceId, "relation-abort"))
                .containsOnly(Map.entry("anchors", 0L), Map.entry("relations", 0L),
                        Map.entry("evidence", 0L), Map.entry("extracted_relations", 0L),
                        Map.entry("supports", 0L), Map.entry("slots", 0L));

        PromotionResult retried = connector.promoteRelation(identity, relation,
                new SourceRevisionIdentity(tenant, sourceId, 2));
        assertThat(retried).isEqualTo(new PromotionResult(1, 1, 1));
        assertThat(promotionArtifacts(tenant, canonicalId, evidenceId, "relation-abort"))
                .containsOnly(Map.entry("anchors", 1L), Map.entry("relations", 1L),
                        Map.entry("evidence", 1L), Map.entry("extracted_relations", 1L),
                        Map.entry("supports", 1L), Map.entry("slots", 2L));

        PromotionResult duplicate = connector.promoteRelation(identity, relation,
                new SourceRevisionIdentity(tenant, sourceId, 2));
        assertThat(duplicate).isEqualTo(new PromotionResult(0, 0, 1));
        assertThat(promotionArtifacts(tenant, canonicalId, evidenceId, "relation-abort"))
                .containsOnly(Map.entry("anchors", 1L), Map.entry("relations", 1L),
                        Map.entry("evidence", 1L), Map.entry("extracted_relations", 1L),
                        Map.entry("supports", 1L), Map.entry("slots", 2L));
    }

    @Test
    void newerGenerationFenceAdvanceDuringRecoveryRejectsGenerationTwoRetry() {
        String tenant = "tenant-abort-stale";
        String sourceId = "source-abort-stale";
        seedResolution(tenant, "subject-abort-stale", "entity-subject-abort-stale");
        seedResolution(tenant, "object-abort-stale", "entity-object-abort-stale");
        cypher.write("""
                MERGE (f:SourceRevisionFence {tenant_id: $tenant, source_id: $sourceId})
                SET f.accepted_generation = 2
                """, Map.of("tenant", tenant, "sourceId", sourceId));
        GraphIdentity identity = GraphIdentity.canonical(tenant, "v1");
        CanonicalRelation relation = relation(tenant, "relation-abort-stale", "revision-abort-stale",
                "subject-abort-stale", "entity-subject-abort-stale", "object-abort-stale",
                "entity-object-abort-stale", "evidence-abort-stale", sourceId, 2);
        String canonicalId = PromotionIds.canonicalRelationId(relation);
        String evidenceId = PromotionIds.evidenceId(relation);
        AtomicBoolean injected = new AtomicBoolean();
        Neo4jGraphConnector aborting = new Neo4jGraphConnector(mock(GraphLoader.class), cypher, () -> {
            if (injected.compareAndSet(false, true)) {
                throw new InjectedAbort();
            }
        });

        assertThatThrownBy(() -> aborting.promoteRelation(identity, relation,
                new SourceRevisionIdentity(tenant, sourceId, 2)))
                .isInstanceOf(RetryableProcessingException.class);

        cypher.write("""
                MATCH (f:SourceRevisionFence {tenant_id: $tenant, source_id: $sourceId})
                SET f.accepted_generation = 3
                """, Map.of("tenant", tenant, "sourceId", sourceId));

        assertThatThrownBy(() -> connector.promoteRelation(identity, relation,
                new SourceRevisionIdentity(tenant, sourceId, 2)))
                .isInstanceOf(StaleGenerationException.class);
        assertThat(promotionArtifacts(tenant, canonicalId, evidenceId, "relation-abort-stale"))
                .containsOnly(Map.entry("anchors", 0L), Map.entry("relations", 0L),
                        Map.entry("evidence", 0L), Map.entry("extracted_relations", 0L),
                        Map.entry("supports", 0L), Map.entry("slots", 0L));
    }

    @Test
    void concurrentFenceAdvanceCannotOutrunInFlightGenerationTwoPromotion() throws Exception {
        String tenant = "tenant-fence-in-flight";
        String sourceId = "source-fence-in-flight";
        seedResolution(tenant, "subject-in-flight", "entity-subject-in-flight");
        seedResolution(tenant, "object-in-flight", "entity-object-in-flight");
        cypher.write("""
                MERGE (f:SourceRevisionFence {tenant_id: $tenant, source_id: $sourceId})
                SET f.accepted_generation = 2
                """, Map.of("tenant", tenant, "sourceId", sourceId));
        GraphIdentity identity = GraphIdentity.canonical(tenant, "v1");
        CanonicalRelation relation = relation(tenant, "relation-in-flight", "revision-in-flight",
                "subject-in-flight", "entity-subject-in-flight", "object-in-flight",
                "entity-object-in-flight", "evidence-in-flight", sourceId, 2);
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean injected = new AtomicBoolean();
        Neo4jGraphConnector blocking = new Neo4jGraphConnector(mock(GraphLoader.class), cypher, () -> {
            if (injected.compareAndSet(false, true)) {
                staged.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release promotion");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        });
        Neo4jSourceFenceStore fences = new Neo4jSourceFenceStore(cypher);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PromotionResult> promotion = pool.submit(() -> blocking.promoteRelation(identity, relation,
                    new SourceRevisionIdentity(tenant, sourceId, 2)));
            assertThat(staged.await(10, TimeUnit.SECONDS)).isTrue();
            Future<SourceFenceAdvance> advance = pool.submit(() ->
                    fences.advance(new SourceFenceKey(tenant, sourceId), 3));
            Thread.sleep(250);
            assertThat(advance.isDone()).as("fence advance must wait for the in-flight promotion lock").isFalse();
            release.countDown();
            assertThat(promotion.get(30, TimeUnit.SECONDS)).isEqualTo(new PromotionResult(1, 1, 1));
            assertThat(advance.get(30, TimeUnit.SECONDS).generation()).isEqualTo(3);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(relationEvidenceCount(tenant, "relation-in-flight")).isEqualTo(1);
        assertThat(fences.read(new SourceFenceKey(tenant, sourceId))).contains(3L);
    }

    @Test
    void concurrentGenerationTwoAndThreePromotionHasDeterministicLoser() throws Exception {
        String tenant = "tenant-generation-concurrent";
        String sourceId = "source-generation-concurrent";
        seedResolution(tenant, "subject-concurrent", "entity-subject-concurrent");
        seedResolution(tenant, "object-concurrent", "entity-object-concurrent");
        cypher.write("""
                MERGE (f:SourceRevisionFence {tenant_id: $tenant, source_id: $sourceId})
                SET f.accepted_generation = 3
                """, Map.of("tenant", tenant, "sourceId", sourceId));
        GraphIdentity identity = GraphIdentity.canonical(tenant, "v1");
        CanonicalRelation gen2 = relation(tenant, "relation-concurrent-gen2", "revision-concurrent-gen2",
                "subject-concurrent", "entity-subject-concurrent", "object-concurrent",
                "entity-object-concurrent", "evidence-concurrent-gen2", sourceId, 2);
        CanonicalRelation gen3 = relation(tenant, "relation-concurrent-gen3", "revision-concurrent-gen3",
                "subject-concurrent", "entity-subject-concurrent", "object-concurrent",
                "entity-object-concurrent", "evidence-concurrent-gen3", sourceId, 3);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> loser = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> connector.promoteRelation(identity, gen2,
                        new SourceRevisionIdentity(tenant, sourceId, 2)))
                        .isInstanceOf(StaleGenerationException.class);
                return null;
            });
            Future<PromotionResult> winner = pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return connector.promoteRelation(identity, gen3,
                        new SourceRevisionIdentity(tenant, sourceId, 3));
            });
            start.countDown();
            loser.get(30, TimeUnit.SECONDS);
            winner.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(relationEvidenceCount(tenant, "relation-concurrent-gen2")).isZero();
        assertThat(relationEvidenceCount(tenant, "relation-concurrent-gen3")).isEqualTo(1);
    }

    private static void seedResolution(String tenant, String mentionId, String entityId) {
        cypher.write("""
                MERGE (m:ExtractedMention {tenant_id: $tenant, mention_id: $mentionId})
                MERGE (e:Entity {tenant: $tenant, id: $entityId})
                MERGE (m)-[r:RESOLVES_TO {active: true}]->(e)
                SET r.resolution_id = $mentionId, r.fencing_token = 1
                """, Map.of("tenant", tenant, "mentionId", mentionId, "entityId", entityId));
    }

    private static long relationEvidenceCount(String tenant, String relationId) {
        return ((Number) cypher.read("""
                MATCH (e:RelationEvidence {tenant_id: $tenant, extracted_relation_id: $relationId})
                RETURN count(e) AS c
                """, Map.of("tenant", tenant, "relationId", relationId)).getFirst().get("c")).longValue();
    }

    private static Entity entity(UUID id, String label, String type) {
        return new Entity(id, label, type, 1.0, List.of());
    }

    private static Map<String, Long> promotionArtifacts(String tenant, String canonicalId,
                                                        String evidenceId, String extractedRelationId) {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        counts.put("anchors", count("""
                MATCH (a:CanonicalRelationAnchor {tenant_id: $tenant, canonical_relation_id: $canonicalId})
                RETURN count(a) AS c
                """, Map.of("tenant", tenant, "canonicalId", canonicalId)));
        counts.put("relations", count("""
                MATCH ()-[r:REL {tenant_id: $tenant, canonical_relation_id: $canonicalId}]->()
                RETURN count(r) AS c
                """, Map.of("tenant", tenant, "canonicalId", canonicalId)));
        counts.put("evidence", count("""
                MATCH (e:RelationEvidence {tenant_id: $tenant, evidence_id: $evidenceId})
                RETURN count(e) AS c
                """, Map.of("tenant", tenant, "evidenceId", evidenceId)));
        counts.put("extracted_relations", count("""
                MATCH (r:ExtractedRelation {tenant_id: $tenant, relation_id: $extractedRelationId})
                RETURN count(r) AS c
                """, Map.of("tenant", tenant, "extractedRelationId", extractedRelationId)));
        counts.put("supports", count("""
                MATCH (:ExtractedRelation {tenant_id: $tenant, relation_id: $extractedRelationId})
                      -[s:SUPPORTS]->(:RelationEvidence {tenant_id: $tenant, evidence_id: $evidenceId})
                RETURN count(s) AS c
                """, Map.of("tenant", tenant, "extractedRelationId", extractedRelationId,
                "evidenceId", evidenceId)));
        counts.put("slots", count("""
                MATCH (s:ResolutionSlot {tenant_id: $tenant})
                WHERE s.mention_id IN ['subject-abort', 'object-abort',
                      'subject-abort-stale', 'object-abort-stale']
                RETURN count(s) AS c
                """, Map.of("tenant", tenant)));
        return counts;
    }

    private static long count(String query, Map<String, Object> params) {
        return ((Number) cypher.read(query, params).getFirst().get("c")).longValue();
    }

    private static long count(String query, String tenant) {
        return count(query, Map.of("tenant", tenant));
    }

    private static final class InjectedAbort extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private InjectedAbort() {
            super("injected abort after fence check and staged writes");
        }
    }
}
