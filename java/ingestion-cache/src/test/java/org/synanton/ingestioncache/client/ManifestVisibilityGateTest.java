package org.synanton.ingestioncache.client;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.claim.PromotionOwnershipStore;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.KnowledgeProcessingRunRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manifest visibility gate (#251 review): a manifest whose publishing run has
 * not reached COMPLETED is invisible to every read path — point reads and the
 * search-index rebuild (listManifest) alike — so the PUBLISHING window never
 * leaks partial knowledge into search.
 */
class ManifestVisibilityGateTest {

    private static final CassandraContainer cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
    private static IngestionCacheClient client;
    private static PromotionOwnershipStore ownership;

    @BeforeAll
    static void startInfra() {
        cassandra.start();
        InetSocketAddress contact =
                new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042));
        try (var admin = com.datastax.oss.driver.api.core.CqlSession.builder()
                .addContactPoint(contact)
                .withLocalDatacenter("datacenter1")
                .build()) {
            admin.execute("CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                    + "{'class':'SimpleStrategy','replication_factor':1}");
        }
        var session = com.datastax.oss.driver.api.core.CqlSession.builder()
                .addContactPoint(contact)
                .withLocalDatacenter("datacenter1")
                .withKeyspace("ingestion_cache")
                .build();
        SchemaInstaller.install(session);
        client = new IngestionCacheClient(session);
        ownership = new PromotionOwnershipStore(session);
    }

    @AfterAll
    static void stopInfra() {
        cassandra.stop();
    }

    private static void insertPromotionRow(RelationPromotionResultRow row) {
        PromotionOwnershipStore.OwnershipToken token = ownership.acquire(row.tenantId(),
                row.sourceVersionId(), "test-seed").orElseThrow();
        ownership.insertPromotionResult(row, token);
    }

    private static ManifestRow manifest(String tenantId, UUID contentRefId, UUID runId) {
        return new ManifestRow(tenantId, contentRefId, Instant.now(), 1, "semantic-v1", 1,
                "CHUNKED", "CONTENT_CACHE", "", "file:///doc", "sha256:x", 10, "text/markdown",
                "FULL", null, "", null, runId);
    }

    private static KnowledgeProcessingRunRow run(String tenantId, UUID runId, String status) {
        return new KnowledgeProcessingRunRow(tenantId, runId, "synflux-knowledge", "semantic-v1:1",
                "{}", "{}", null, status, Instant.now(), "COMPLETED".equals(status) ? Instant.now() : null,
                null, null, java.util.Set.of());
    }

    @Test
    void manifestIsInvisibleUntilItsPublishingRunCompletes() {
        String tenant = "tenant-visibility-" + UUID.randomUUID();
        UUID contentRefId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        client.upsertKnowledgeProcessingRun(run(tenant, runId, "RUNNING"));
        client.upsertManifest(manifest(tenant, contentRefId, runId));

        // RUNNING and PUBLISHING: the manifest exists but no read path serves it.
        assertThat(client.readManifest(tenant, contentRefId)).isEmpty();
        assertThat(client.listManifest(tenant, 100)).isEmpty();

        client.upsertKnowledgeProcessingRun(run(tenant, runId, "COMPLETED"));
        assertThat(client.readManifest(tenant, contentRefId)).isPresent();
        assertThat(client.listManifest(tenant, 100))
                .extracting(ManifestRow::contentRefId)
                .containsExactly(contentRefId);
    }

    @Test
    void relationPromotionIdentitySurvivesTheCassandraRoundTrip() {
        String tenant = "tenant-promotion-identity-" + UUID.randomUUID();
        UUID version = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        insertPromotionRow(new RelationPromotionResultRow(
                tenant, version, "relation-a", "m1", "m2", "requires", "VALID",
                "entity-1", "entity-2", "VALID", "{}", "v1", "active", Instant.now(), null,
                "source-a", revision, 5L, "run-a", "CURRENT"));

        RelationPromotionResultRow stored = client.listRelationPromotionResults(tenant, version)
                .getFirst();
        assertThat(stored.sourceId()).isEqualTo("source-a");
        assertThat(stored.revisionId()).isEqualTo(revision);
        assertThat(stored.generation()).isEqualTo(5L);
        assertThat(stored.extractionRunId()).isEqualTo("run-a");
        assertThat(stored.identityStatus()).isEqualTo("CURRENT");
    }

    @Test
    void legacyPromotionRowStaysExplicitlyUnversioned() {
        String tenant = "tenant-promotion-legacy-" + UUID.randomUUID();
        UUID version = UUID.randomUUID();
        insertPromotionRow(new RelationPromotionResultRow(
                tenant, version, "relation-legacy", "m1", "m2", "requires", "VALID",
                "entity-1", "entity-2", "VALID", "{}", "v1", "active", Instant.now(), null));

        RelationPromotionResultRow stored = client.listRelationPromotionResults(tenant, version)
                .getFirst();
        assertThat(stored.identityStatus()).isEqualTo("LEGACY_UNVERSIONED");
        assertThat(stored.generation()).isZero();
        assertThat(stored.sourceId()).isNull();
    }

    @Test
    void multiSourceEvidenceKeepsPerSourceGenerations() {
        String tenant = "tenant-promotion-multi-" + UUID.randomUUID();
        UUID versionA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        insertPromotionRow(new RelationPromotionResultRow(
                tenant, versionA, "relation-a", "m1", "m2", "requires", "VALID",
                "entity-1", "entity-2", "VALID", "{}", "v1", "active", Instant.now(), null,
                "source-a", UUID.randomUUID(), 5L, "run-a", "CURRENT"));
        insertPromotionRow(new RelationPromotionResultRow(
                tenant, versionB, "relation-b", "m1", "m2", "requires", "VALID",
                "entity-1", "entity-2", "VALID", "{}", "v1", "active", Instant.now(), null,
                "source-b", UUID.randomUUID(), 2L, "run-b", "CURRENT"));

        RelationPromotionResultRow a = client.listRelationPromotionResults(tenant, versionA).getFirst();
        RelationPromotionResultRow b = client.listRelationPromotionResults(tenant, versionB).getFirst();
        assertThat(a.sourceId()).isEqualTo("source-a");
        assertThat(a.generation()).isEqualTo(5L);
        assertThat(b.sourceId()).isEqualTo("source-b");
        assertThat(b.generation()).isEqualTo(2L);
        assertThat(a.canonicalSubjectId()).isEqualTo(b.canonicalSubjectId());
    }

    @Test
    void failedPublishingRunLeavesManifestInvisible() {
        String tenant = "tenant-visibility-failed-" + UUID.randomUUID();
        UUID contentRefId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        client.upsertKnowledgeProcessingRun(run(tenant, runId, "PUBLISHING"));
        client.upsertManifest(manifest(tenant, contentRefId, runId));
        client.upsertKnowledgeProcessingRun(run(tenant, runId, "FAILED"));

        assertThat(client.readManifest(tenant, contentRefId)).isEmpty();
        assertThat(client.listManifest(tenant, 100)).isEmpty();
    }

    @Test
    void legacyManifestsWithoutProcessingRunRemainVisible() {
        String tenant = "tenant-visibility-legacy-" + UUID.randomUUID();
        UUID contentRefId = UUID.randomUUID();

        client.upsertManifest(manifest(tenant, contentRefId, null));

        assertThat(client.readManifest(tenant, contentRefId)).isPresent();
        assertThat(client.listManifest(tenant, 100))
                .extracting(ManifestRow::contentRefId)
                .containsExactly(contentRefId);
    }
}
