package org.synanton.ingestioncache.quarantine;

import static org.assertj.core.api.Assertions.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

class QuarantineStoreTest {

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static QuarantineStore store;

    @BeforeAll
    static void startCassandra() {
        cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
        cassandra.start();
        InetSocketAddress contact = new InetSocketAddress(cassandra.getHost(),
                cassandra.getMappedPort(9042));
        try (CqlSession admin = CqlSession.builder().addContactPoint(contact)
                .withLocalDatacenter("datacenter1").build()) {
            admin.execute("CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                    + "{'class':'SimpleStrategy','replication_factor':1}");
        }
        session = CqlSession.builder().addContactPoint(contact)
                .withLocalDatacenter("datacenter1").withKeyspace("ingestion_cache").build();
        SchemaInstaller.install(session);
        store = new QuarantineStore(session);
    }

    @AfterAll
    static void stopCassandra() {
        if (session != null) {
            session.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @Test
    void generationRecordsAreIsolatedAndAppendIsIdempotent() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.createGeneration(staged(tenant, sourceVersion, first));
        store.createGeneration(staged(tenant, sourceVersion, second));
        QuarantineRecord record = record(tenant, sourceVersion, first, 1);
        store.append(record);
        store.append(record);

        assertThat(store.records(tenant, sourceVersion, first)).hasSize(1);
        assertThat(store.records(tenant, sourceVersion, second)).isEmpty();
        assertThat(store.generations(tenant, sourceVersion)).hasSize(2);
        assertThat(store.currentGeneration(tenant, sourceVersion)).isEmpty();
    }

    @Test
    void generationPersistsOntologyReleaseIdentity() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        store.createGeneration(QuarantineGeneration.staged(tenant, sourceVersion, generation,
                "doc", "extraction-v2", "a".repeat(64), "b".repeat(64),
                "qwen3-8b-fp8", "rev", Instant.now(), UUID.randomUUID(),
                "release-1", "d".repeat(64)));

        QuarantineGeneration row = store.generations(tenant, sourceVersion).getFirst();
        assertThat(row.ontologyReleaseId()).isEqualTo("release-1");
        assertThat(row.ontologyReleaseDigest()).isEqualTo("d".repeat(64));
    }

    @Test
    void promotionMakesGenerationCurrentAndSupersedesPrevious() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.createGeneration(staged(tenant, sourceVersion, first));
        store.createGeneration(staged(tenant, sourceVersion, second));

        store.promote(tenant, sourceVersion, first, "test", "first");
        assertThat(store.currentGeneration(tenant, sourceVersion)).contains(first);

        store.promote(tenant, sourceVersion, second, "test", "second");
        assertThat(store.currentGeneration(tenant, sourceVersion)).contains(second);
        List<QuarantineGeneration> generations = store.generations(tenant, sourceVersion);
        QuarantineGeneration firstRow = generations.stream()
                .filter(row -> row.extractionGeneration().equals(first)).findFirst().orElseThrow();
        QuarantineGeneration secondRow = generations.stream()
                .filter(row -> row.extractionGeneration().equals(second)).findFirst().orElseThrow();
        assertThat(firstRow.generationStatus()).isEqualTo("SUPERSEDED");
        assertThat(firstRow.supersededBy()).isEqualTo(second);
        assertThat(secondRow.promotedAt()).isNotNull();
        assertThat(secondRow.previousGeneration()).isEqualTo(first);
    }

    @Test
    void concurrentPromotionsLeaveExactlyOneCurrentPointer() throws Exception {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.createGeneration(staged(tenant, sourceVersion, first));
        store.createGeneration(staged(tenant, sourceVersion, second));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<QuarantineStore.PromotionResult> a = pool.submit(() -> {
                start.await();
                return store.promote(tenant, sourceVersion, first, "a", "race");
            });
            Future<QuarantineStore.PromotionResult> b = pool.submit(() -> {
                start.await();
                return store.promote(tenant, sourceVersion, second, "b", "race");
            });
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        UUID current = store.currentGeneration(tenant, sourceVersion).orElseThrow();
        assertThat(current).isIn(first, second);
        assertThat(store.generations(tenant, sourceVersion)).hasSize(2);
    }

    @Test
    void promotingTheSameGenerationIsIdempotent() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        store.createGeneration(staged(tenant, sourceVersion, generation));

        store.promote(tenant, sourceVersion, generation, "test", "first");
        store.promote(tenant, sourceVersion, generation, "test", "again");

        assertThat(store.currentGeneration(tenant, sourceVersion)).contains(generation);
        assertThat(store.generations(tenant, sourceVersion)).hasSize(1);
    }

    @Test
    void tombstoneKeepsRecordsAndHidesCurrent() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        store.createGeneration(staged(tenant, sourceVersion, generation));
        store.append(record(tenant, sourceVersion, generation, 1));
        store.promote(tenant, sourceVersion, generation, "test", "promote");

        int affected = store.deleteSource(tenant, sourceVersion,
                QuarantineStore.DeletionMode.TOMBSTONE, "source deleted");

        assertThat(affected).isEqualTo(1);
        assertThat(store.currentGeneration(tenant, sourceVersion)).isEmpty();
        assertThat(store.records(tenant, sourceVersion)).hasSize(1);
        QuarantineGeneration row = store.generations(tenant, sourceVersion).getFirst();
        assertThat(row.generationStatus()).isEqualTo("DELETED");
        assertThat(row.deletionMode()).isEqualTo("TOMBSTONE");
        assertThat(row.deletedAt()).isNotNull();
    }

    @Test
    void hardDeleteRemovesGovernedRows() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        store.createGeneration(staged(tenant, sourceVersion, generation));
        store.append(record(tenant, sourceVersion, generation, 1));
        store.promote(tenant, sourceVersion, generation, "test", "promote");

        int affected = store.deleteSource(tenant, sourceVersion,
                QuarantineStore.DeletionMode.HARD_DELETE, "compliance");

        assertThat(affected).isEqualTo(1);
        assertThat(store.currentGeneration(tenant, sourceVersion)).isEmpty();
        assertThat(store.records(tenant, sourceVersion)).isEmpty();
        assertThat(store.generations(tenant, sourceVersion)).isEmpty();
    }

    @Test
    void generationRecordsAndAuditSurviveASecondSessionRead() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        store.createGeneration(staged(tenant, sourceVersion, generation));
        store.append(record(tenant, sourceVersion, generation, 1));
        store.promote(tenant, sourceVersion, generation, "reviewer", "promote");
        store.recordAudit(QuarantineAuditEvent.of(tenant, sourceVersion, generation,
                "REVIEW_NOTE", "reviewer", "checked", "durable", Instant.now()));

        InetSocketAddress contact = new InetSocketAddress(cassandra.getHost(),
                cassandra.getMappedPort(9042));
        try (CqlSession restarted = CqlSession.builder().addContactPoint(contact)
                .withLocalDatacenter("datacenter1").withKeyspace("ingestion_cache").build()) {
            QuarantineStore reopened = new QuarantineStore(restarted);
            assertThat(reopened.generations(tenant, sourceVersion)).hasSize(1);
            assertThat(reopened.records(tenant, sourceVersion, generation)).hasSize(1);
            assertThat(reopened.currentGeneration(tenant, sourceVersion)).contains(generation);
            assertThat(reopened.auditEvents(tenant, sourceVersion, generation))
                    .extracting(QuarantineAuditEvent::eventType)
                    .contains("PROMOTION_APPLIED", "REVIEW_NOTE");
        }
    }

    @Test
    void failedGenerationIsMarkedFailedAndAudited() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        store.createGeneration(staged(tenant, sourceVersion, generation));

        store.markFailed(tenant, sourceVersion, generation, "system", "model failure");

        QuarantineGeneration row = store.generations(tenant, sourceVersion).getFirst();
        assertThat(row.generationStatus()).isEqualTo("FAILED");
        assertThat(store.currentGeneration(tenant, sourceVersion)).isEmpty();
        assertThat(store.auditEvents(tenant, sourceVersion, generation))
                .extracting(QuarantineAuditEvent::eventType)
                .contains("GENERATION_FAILED");
    }

    @Test
    void promotionAndDeletionWriteDurableAuditEvents() {
        String tenant = tenant();
        UUID sourceVersion = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        store.createGeneration(staged(tenant, sourceVersion, generation));
        store.append(record(tenant, sourceVersion, generation, 1));
        store.promote(tenant, sourceVersion, generation, "reviewer", "promote");

        assertThat(store.auditEvents(tenant, sourceVersion, generation))
                .extracting(QuarantineAuditEvent::eventType)
                .contains("PROMOTION_ATTEMPTED", "PROMOTION_APPLIED");

        store.deleteSource(tenant, sourceVersion, QuarantineStore.DeletionMode.TOMBSTONE, "source deleted");

        assertThat(store.auditEvents(tenant, sourceVersion, generation))
                .extracting(QuarantineAuditEvent::eventType)
                .contains("TOMBSTONE_APPLIED");
        assertThat(store.records(tenant, sourceVersion)).hasSize(1);
    }

    @Test
    void deletionIsScopedToTenantAndSourceVersion() {
        String tenantA = tenant();
        String tenantB = tenant();
        UUID versionA = UUID.randomUUID();
        UUID versionB = UUID.randomUUID();
        UUID generationA = UUID.randomUUID();
        UUID generationB = UUID.randomUUID();
        store.createGeneration(staged(tenantA, versionA, generationA));
        store.createGeneration(staged(tenantB, versionB, generationB));
        store.append(record(tenantA, versionA, generationA, 1));
        store.append(record(tenantB, versionB, generationB, 1));
        store.promote(tenantA, versionA, generationA, "test", "a");
        store.promote(tenantB, versionB, generationB, "test", "b");

        store.deleteSource(tenantA, versionA, QuarantineStore.DeletionMode.HARD_DELETE, "test");

        assertThat(store.records(tenantA, versionA)).isEmpty();
        assertThat(store.generations(tenantA, versionA)).isEmpty();
        assertThat(store.records(tenantB, versionB)).hasSize(1);
        assertThat(store.currentGeneration(tenantB, versionB)).contains(generationB);
    }

    private static String tenant() {
        return "tenant-" + UUID.randomUUID();
    }

    private static QuarantineGeneration staged(String tenant, UUID sourceVersion, UUID generation) {
        return QuarantineGeneration.staged(tenant, sourceVersion, generation,
                "doc-" + tenant, "extraction-v2", "a".repeat(64), "b".repeat(64),
                "qwen3-8b-fp8", "rev", Instant.now(), UUID.randomUUID());
    }

    private static QuarantineRecord record(String tenant, UUID sourceVersion, UUID generation,
                                           int lineNumber) {
        return QuarantineRecord.open(tenant, sourceVersion, generation, 0, lineNumber,
                "JSON_PARSE", "STRUCTURAL", "bad line", "c".repeat(64), "{bad}",
                Instant.now());
    }
}
