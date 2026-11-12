package org.synanton.relix.adapter.out.graph.neo4j;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.resolution.SourceRevisionIdentity;
import org.synanton.common.resolution.StaleGenerationException;
import org.synanton.relix.api.dto.Edge;
import org.synanton.relix.api.dto.Entity;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.identity.UnsupportedGraphIdException;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    void staleGenerationCannotMutateOrRetireNewerEvidence() {
        String tenant = "tenant-generation-fence";
        String sourceId = "source-generation-fence";
        seedCanonicalResolution(tenant, "subject-3", "entity-subject-3");
        seedCanonicalResolution(tenant, "object-3", "entity-object-3");
        cypher.write("""
                MERGE (f:SourceRevisionFence {tenant_id: $tenant, source_id: $sourceId})
                SET f.accepted_generation = 3, f.current_generation = 3
                """, Map.of("tenant", tenant, "sourceId", sourceId));
        GraphIdentity identity = GraphIdentity.canonical(tenant, "v1");

        CanonicalRelation gen3 = relation(tenant, "relation-gen3", "revision-gen3", "subject-3",
                "entity-subject-3", "object-3", "entity-object-3", "evidence-gen3");
        connector.promoteRelation(identity, gen3, new SourceRevisionIdentity(tenant, sourceId, 3));
        assertThat(evidenceCount(tenant, "relation-gen3")).isEqualTo(1);

        CanonicalRelation gen2 = relation(tenant, "relation-gen2", "revision-gen2", "subject-3",
                "entity-subject-3", "object-3", "entity-object-3", "evidence-gen2");
        assertThatThrownBy(() -> connector.promoteRelation(identity, gen2,
                new SourceRevisionIdentity(tenant, sourceId, 2)))
                .isInstanceOf(StaleGenerationException.class);

        assertThat(evidenceCount(tenant, "relation-gen2")).isZero();
        assertThat(evidenceCount(tenant, "relation-gen3")).isEqualTo(1);
    }

    private static void seedCanonicalResolution(String tenant, String mentionId, String entityId) {
        cypher.write("""
                MERGE (m:ExtractedMention {tenant_id: $tenant, mention_id: $mentionId})
                MERGE (e:Entity {tenant: $tenant, id: $entityId})
                MERGE (m)-[r:RESOLVES_TO {active: true}]->(e)
                SET r.resolution_id = $mentionId, r.fencing_token = 1
                """, Map.of("tenant", tenant, "mentionId", mentionId, "entityId", entityId));
    }

    private static long evidenceCount(String tenant, String relationId) {
        return ((Number) cypher.read("""
                MATCH (e:RelationEvidence {tenant_id: $tenant, extracted_relation_id: $relationId})
                RETURN count(e) AS c
                """, Map.of("tenant", tenant, "relationId", relationId)).getFirst().get("c")).longValue();
    }

    private static CanonicalRelation relation(String tenant, String relationId, String revisionId,
                                              String subjectMentionId, String subjectEntityId,
                                              String objectMentionId, String objectEntityId,
                                              String evidenceChunkId) {
        return new CanonicalRelation(tenant, revisionId, relationId, subjectMentionId, subjectEntityId,
                objectMentionId, objectEntityId, "requires", "active", evidenceChunkId, 0, 10);
    }

    private static Entity entity(UUID id, String label, String type) {
        return new Entity(id, label, type, 1.0, List.of());
    }

    private static long count(String query, String tenant) {
        return ((Number) cypher.read(query, Map.of("tenant", tenant)).getFirst().get("c")).longValue();
    }
}
