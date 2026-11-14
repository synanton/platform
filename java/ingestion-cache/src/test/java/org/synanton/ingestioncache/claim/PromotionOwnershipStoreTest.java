package org.synanton.ingestioncache.claim;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.StaleOwnershipException;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromotionOwnershipStoreTest {

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static PromotionOwnershipStore store;

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
        store = new PromotionOwnershipStore(session);
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
    void initialAcquisitionRaceYieldsExactlyOneOwner() throws Exception {
        String tenant = "tenant-ownership-acquire";
        UUID version = UUID.randomUUID();
        List<Optional<PromotionOwnershipStore.OwnershipToken>> tokens =
                Collections.synchronizedList(new ArrayList<>());

        List<Throwable> errors = runTogether(
                () -> tokens.add(store.acquire(tenant, version, "producer-a")),
                () -> tokens.add(store.acquire(tenant, version, "producer-b")));

        assertThat(errors).isEmpty();
        List<PromotionOwnershipStore.OwnershipToken> acquired = tokens.stream()
                .filter(Optional::isPresent).map(Optional::get).toList();
        assertThat(acquired).hasSize(1);
        PromotionOwnershipStore.OwnershipRow row = store.read(tenant, version).orElseThrow();
        assertThat(row.ownerProducerId()).isEqualTo(acquired.getFirst().producerId());
        assertThat(row.ownerGeneration()).isEqualTo(1);
        assertThat(row.closed()).isFalse();
        assertThat(sentinelCount(tenant, version)).isEqualTo(1);
    }

    @Test
    void staleWriteAfterTakeoverWritesNoRow() {
        String tenant = "tenant-ownership-stale";
        UUID version = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken generationOne =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        PromotionOwnershipStore.OwnershipToken generationTwo =
                store.takeover(tenant, version, "producer-b", 1).orElseThrow();

        assertThat(generationTwo.generation()).isEqualTo(2);
        assertThatThrownBy(() -> store.writeFenced(tenant, version,
                rowInsert(tenant, version, "relation-stale"), generationOne))
                .isInstanceOf(StaleOwnershipException.class);
        assertThat(rowExists(tenant, version, "relation-stale")).isFalse();
        assertThat(store.read(tenant, version).orElseThrow())
                .satisfies(row -> {
                    assertThat(row.ownerProducerId()).isEqualTo("producer-b");
                    assertThat(row.ownerGeneration()).isEqualTo(2);
                    assertThat(row.takeoverCount()).isEqualTo(1);
                });

        assertThat(store.writeFenced(tenant, version,
                rowInsert(tenant, version, "relation-current"), generationTwo)).isTrue();
        assertThat(rowExists(tenant, version, "relation-current")).isTrue();
    }

    @Test
    void rowWriteAndClosureRaceNeverWritesAfterClose() throws Exception {
        String tenant = "tenant-ownership-row-close";
        boolean sawWritten = false;
        boolean sawRejected = false;
        for (int iteration = 0; iteration < 30; iteration++) {
            UUID version = UUID.randomUUID();
            PromotionOwnershipStore.OwnershipToken token =
                    store.acquire(tenant, version, "producer-" + iteration).orElseThrow();
            String relationId = "relation-race-" + iteration;
            AtomicBoolean writeSucceeded = new AtomicBoolean();
            AtomicBoolean writeRejected = new AtomicBoolean();
            AtomicBoolean closeSucceeded = new AtomicBoolean();
            AtomicBoolean closeRejected = new AtomicBoolean();

            List<Throwable> errors = runTogether(
                    () -> {
                        try {
                            store.writeFenced(tenant, version,
                                    rowInsert(tenant, version, relationId), token);
                            writeSucceeded.set(true);
                        } catch (StaleOwnershipException e) {
                            writeRejected.set(true);
                        }
                    },
                    () -> {
                        try {
                            store.closeSet(tenant, version, token);
                            closeSucceeded.set(true);
                        } catch (StaleOwnershipException e) {
                            closeRejected.set(true);
                        }
                    });

            assertThat(errors).isEmpty();
            assertThat(closeSucceeded).isTrue();
            assertThat(closeRejected).isFalse();
            PromotionOwnershipStore.OwnershipRow row = store.read(tenant, version).orElseThrow();
            assertThat(row.closed()).isTrue();
            boolean rowPresent = rowExists(tenant, version, relationId);
            if (writeSucceeded.get()) {
                assertThat(rowPresent).isTrue();
            } else {
                assertThat(writeRejected).isTrue();
                assertThat(rowPresent).isFalse();
            }
            sawWritten |= writeSucceeded.get();
            sawRejected |= writeRejected.get();
        }

        UUID closedVersion = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken closedToken =
                store.acquire(tenant, closedVersion, "producer-closed").orElseThrow();
        store.closeSet(tenant, closedVersion, closedToken);
        assertThatThrownBy(() -> store.writeFenced(tenant, closedVersion,
                rowInsert(tenant, closedVersion, "relation-after-close"), closedToken))
                .isInstanceOf(StaleOwnershipException.class);
        assertThat(rowExists(tenant, closedVersion, "relation-after-close")).isFalse();
        assertThat(sawWritten || sawRejected).isTrue();
    }

    @Test
    void insertPromotionResultWritesAllColumnsAndClaimStatus() {
        String tenant = "tenant-ownership-insert";
        UUID version = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken token =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        RelationPromotionResultRow valid = promotionRow(tenant, version, revision,
                "relation-insert-valid", "VALID");
        RelationPromotionResultRow invalid = promotionRow(tenant, version, revision,
                "relation-insert-invalid", "INVALID");

        assertThat(store.insertPromotionResult(valid, token)).isTrue();
        assertThat(store.insertPromotionResult(invalid, token)).isTrue();

        Row validRow = promotionRow(tenant, version, "relation-insert-valid");
        assertThat(validRow.getString("claim_status")).isEqualTo("PENDING");
        assertThat(validRow.getString("source_id")).isEqualTo("source-a");
        assertThat(validRow.getUuid("revision_id")).isEqualTo(revision);
        assertThat(validRow.getLong("generation")).isEqualTo(5L);
        assertThat(validRow.getString("extraction_run_id")).isEqualTo("run-a");
        assertThat(validRow.getString("identity_status")).isEqualTo("CURRENT");
        assertThat(promotionRow(tenant, version, "relation-insert-invalid")
                .getString("claim_status")).isEqualTo("COMPLETED");
    }

    @Test
    void staleInsertPromotionResultAfterTakeoverWritesNoRow() {
        String tenant = "tenant-ownership-insert-stale";
        UUID version = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken generationOne =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        PromotionOwnershipStore.OwnershipToken generationTwo =
                store.takeover(tenant, version, "producer-b", 1).orElseThrow();
        RelationPromotionResultRow stale = promotionRow(tenant, version, revision,
                "relation-insert-stale", "VALID");

        assertThatThrownBy(() -> store.insertPromotionResult(stale, generationOne))
                .isInstanceOf(StaleOwnershipException.class);
        assertThat(rowExists(tenant, version, "relation-insert-stale")).isFalse();
        assertThat(store.insertPromotionResult(stale, generationTwo)).isTrue();
        assertThat(rowExists(tenant, version, "relation-insert-stale")).isTrue();
    }

    @Test
    void outcomeUpdateIsAllowedBeforeAndAfterClosure() {
        String tenant = "tenant-ownership-outcome";
        UUID version = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken token =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        assertThat(store.writeFenced(tenant, version, rowInsert(tenant, version, "relation-outcome"),
                token)).isTrue();

        assertThat(store.updateOutcomeFenced(tenant, version, "relation-outcome",
                "COMPLETED", token)).isTrue();
        assertThat(promotionRow(tenant, version, "relation-outcome")
                .getString("promotion_status")).isEqualTo("COMPLETED");
        store.closeSet(tenant, version, token);
        assertThat(store.updateOutcomeFenced(tenant, version, "relation-outcome",
                "FAILED", token)).isTrue();
        assertThat(promotionRow(tenant, version, "relation-outcome")
                .getString("promotion_status")).isEqualTo("FAILED");
    }

    @Test
    void staleOutcomeUpdateFailsAfterTakeoverAndLeavesLaterOutcomeUnchanged() {
        String tenant = "tenant-ownership-outcome-stale";
        UUID version = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken generationOne =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        assertThat(store.writeFenced(tenant, version, rowInsert(tenant, version, "relation-outcome-stale"),
                generationOne)).isTrue();
        PromotionOwnershipStore.OwnershipToken generationTwo =
                store.takeover(tenant, version, "producer-b", 1).orElseThrow();

        assertThatThrownBy(() -> store.updateOutcomeFenced(tenant, version,
                "relation-outcome-stale", "COMPLETED", generationOne))
                .isInstanceOf(StaleOwnershipException.class);
        assertThat(promotionRow(tenant, version, "relation-outcome-stale")
                .getString("promotion_status")).isEqualTo("VALID");
        assertThat(store.updateOutcomeFenced(tenant, version,
                "relation-outcome-stale", "COMPLETED", generationTwo)).isTrue();
        assertThatThrownBy(() -> store.updateOutcomeFenced(tenant, version,
                "relation-outcome-stale", "FAILED", generationOne))
                .isInstanceOf(StaleOwnershipException.class);
        assertThat(promotionRow(tenant, version, "relation-outcome-stale")
                .getString("promotion_status")).isEqualTo("COMPLETED");
    }

    @Test
    void staleConsumerCannotOverwriteGenerationTwoOutcome() {
        String tenant = "tenant-ownership-stale-consumer";
        UUID version = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken generationOne =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        assertThat(store.writeFenced(tenant, version,
                rowInsert(tenant, version, "relation-stale-consumer"), generationOne)).isTrue();
        store.closeSet(tenant, version, generationOne);

        PromotionOwnershipStore.OwnershipToken generationTwo =
                store.takeover(tenant, version, "producer-b", 1).orElseThrow();
        assertThat(store.updateOutcomeFenced(tenant, version, "relation-stale-consumer",
                "COMPLETED", generationTwo)).isTrue();

        assertThatThrownBy(() -> store.updateOutcomeFenced(tenant, version,
                "relation-stale-consumer", "FAILED", generationOne))
                .isInstanceOf(StaleOwnershipException.class);
        assertThat(promotionStatus(tenant, version, "relation-stale-consumer"))
                .isEqualTo("COMPLETED");
        assertThat(store.read(tenant, version).orElseThrow().ownerGeneration()).isEqualTo(2);
    }

    @Test
    void takeoverSweepAdvancesGenerationAndFencesOldProducer() {
        String tenant = "tenant-ownership-takeover-sweep";
        UUID version = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken generationOne =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        session.execute(SimpleStatement.newInstance("""
                UPDATE ingestion_cache.relation_promotion_results
                SET owner_started_at=?
                WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                """, Instant.now().minusSeconds(3600), tenant, version,
                PromotionOwnershipStore.OWNERSHIP_RELATION_ID));

        assertThat(store.takeoverStale("producer-sweep", Instant.now().minusSeconds(900), 10))
                .isEqualTo(1);
        PromotionOwnershipStore.OwnershipRow recovered = store.read(tenant, version).orElseThrow();
        assertThat(recovered.ownerGeneration()).isEqualTo(2);
        assertThat(recovered.ownerProducerId()).isEqualTo("producer-sweep");
        assertThatThrownBy(() -> store.writeFenced(tenant, version,
                rowInsert(tenant, version, "relation-after-sweep"), generationOne))
                .isInstanceOf(StaleOwnershipException.class);
    }

    @Test
    void stalledConsumerIsRecoveredByClosedSetTakeover() {
        String tenant = "tenant-ownership-consumer-recovery";
        UUID version = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken generationOne =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        for (int i = 1; i <= 5; i++) {
            assertThat(store.insertPromotionResult(rowFor(tenant, version, "relation-" + i,
                    "PENDING"), generationOne)).isTrue();
        }
        store.closeSet(tenant, version, generationOne);
        assertThat(store.updateOutcomeFenced(tenant, version, "relation-1", "COMPLETED",
                generationOne)).isTrue();
        assertThat(store.updateOutcomeFenced(tenant, version, "relation-2", "COMPLETED",
                generationOne)).isTrue();
        session.execute(SimpleStatement.newInstance("""
                UPDATE ingestion_cache.relation_promotion_results
                SET consumer_last_write_at=?
                WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                """, Instant.now().minusSeconds(3600), tenant, version,
                PromotionOwnershipStore.OWNERSHIP_RELATION_ID));

        assertThat(store.findStaleClosed(Instant.now().minusSeconds(600), 10)).anySatisfy(
                row -> {
                    assertThat(row.tenantId()).isEqualTo(tenant);
                    assertThat(row.sourceVersionId()).isEqualTo(version);
                    assertThat(row.closed()).isTrue();
                });
        assertThat(store.countPendingRows(tenant, version)).isEqualTo(3);
        PromotionOwnershipStore.OwnershipToken generationTwo =
                store.takeoverClosedSet(tenant, version, "producer-recovery", 1).orElseThrow();
        assertThat(generationTwo.generation()).isEqualTo(2);

        assertThatThrownBy(() -> store.updateOutcomeFenced(tenant, version, "relation-3",
                "COMPLETED", generationOne)).isInstanceOf(StaleOwnershipException.class);
        for (int i = 3; i <= 5; i++) {
            assertThat(store.updateOutcomeFenced(tenant, version, "relation-" + i,
                    "COMPLETED", generationTwo)).isTrue();
        }
        assertThat(store.countPendingRows(tenant, version)).isZero();
        assertThat(store.read(tenant, version).orElseThrow())
                .satisfies(row -> {
                    assertThat(row.closed()).isTrue();
                    assertThat(row.ownerGeneration()).isEqualTo(2);
                });
    }

    @Test
    void freshConsumerHeartbeatIsNotTakenOverAndCompleteSetsHaveNoPendingRows() {
        String tenant = "tenant-ownership-consumer-fresh";
        UUID version = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken token =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        assertThat(store.insertPromotionResult(rowFor(tenant, version, "relation-1",
                "PENDING"), token)).isTrue();
        store.closeSet(tenant, version, token);
        assertThat(store.updateOutcomeFenced(tenant, version, "relation-1", "COMPLETED",
                token)).isTrue();

        assertThat(store.findStaleClosed(Instant.now().minusSeconds(600), 10)).isEmpty();
        assertThat(store.countPendingRows(tenant, version)).isZero();
    }

    @Test
    void duplicateClosureHasOneWinnerAndDoesNotReplaceClosedAt() throws Exception {
        String tenant = "tenant-ownership-close";
        UUID version = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken token =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        AtomicBoolean firstSucceeded = new AtomicBoolean();
        AtomicBoolean secondSucceeded = new AtomicBoolean();
        AtomicBoolean firstRejected = new AtomicBoolean();
        AtomicBoolean secondRejected = new AtomicBoolean();

        List<Throwable> errors = runTogether(
                () -> {
                    try {
                        store.closeSet(tenant, version, token);
                        firstSucceeded.set(true);
                    } catch (StaleOwnershipException e) {
                        firstRejected.set(true);
                    }
                },
                () -> {
                    try {
                        store.closeSet(tenant, version, token);
                        secondSucceeded.set(true);
                    } catch (StaleOwnershipException e) {
                        secondRejected.set(true);
                    }
                });

        assertThat(errors).isEmpty();
        assertThat(firstSucceeded.get() ^ secondSucceeded.get()).isTrue();
        assertThat(firstRejected.get() ^ secondRejected.get()).isTrue();
        PromotionOwnershipStore.OwnershipRow closed = store.read(tenant, version).orElseThrow();
        assertThat(closed.closed()).isTrue();
        assertThat(closed.closedAt()).isNotNull();
        Instant firstClosedAt = closed.closedAt();

        assertThatThrownBy(() -> store.closeSet(tenant, version, token))
                .isInstanceOf(StaleOwnershipException.class);
        assertThat(store.read(tenant, version).orElseThrow().closedAt())
                .isEqualTo(firstClosedAt);
    }

    @Test
    void restartAfterClosureCannotReopenTheRevision() {
        String tenant = "tenant-ownership-restart";
        UUID version = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken token =
                store.acquire(tenant, version, "producer-a").orElseThrow();
        store.closeSet(tenant, version, token);
        Instant closedAt = store.read(tenant, version).orElseThrow().closedAt();

        assertThat(store.acquire(tenant, version, "producer-a")).isEmpty();
        assertThat(store.acquire(tenant, version, "producer-restarted")).isEmpty();
        PromotionOwnershipStore.OwnershipToken recovered =
                store.takeover(tenant, version, "producer-restarted", 1).orElseThrow();
        assertThat(recovered.generation()).isEqualTo(2);
        assertThat(store.read(tenant, version).orElseThrow())
                .satisfies(row -> {
                    assertThat(row.closed()).isTrue();
                    assertThat(row.ownerProducerId()).isEqualTo("producer-restarted");
                    assertThat(row.ownerGeneration()).isEqualTo(2);
                    assertThat(row.closedAt()).isEqualTo(closedAt);
                });
        assertThatThrownBy(() -> store.writeFenced(tenant, version,
                rowInsert(tenant, version, "relation-reopened"), recovered))
                .isInstanceOf(StaleOwnershipException.class);
        assertThat(rowExists(tenant, version, "relation-reopened")).isFalse();
    }

    @Test
    void zeroRowRevisionCanBeAcquiredAndClosedImmediately() {
        String tenant = "tenant-ownership-zero-row";
        UUID version = UUID.randomUUID();
        PromotionOwnershipStore.OwnershipToken token =
                store.acquire(tenant, version, "producer-zero").orElseThrow();

        assertThat(store.isClosed(tenant, version)).isFalse();
        assertThat(sentinelCount(tenant, version)).isEqualTo(1);
        assertThat(nonSentinelRowCount(tenant, version)).isZero();

        store.closeSet(tenant, version, token);

        assertThat(store.isClosed(tenant, version)).isTrue();
        assertThat(store.read(tenant, version).orElseThrow())
                .satisfies(row -> {
                    assertThat(row.closed()).isTrue();
                    assertThat(row.ownerGeneration()).isEqualTo(1);
                    assertThat(row.startedAt()).isNotNull();
                    assertThat(row.closedAt()).isNotNull();
                });
        assertThat(nonSentinelRowCount(tenant, version)).isZero();
    }

    private static RelationPromotionResultRow promotionRow(String tenant, UUID version,
                                                           UUID revision, String relationId,
                                                           String outcome) {
        return new RelationPromotionResultRow(
                tenant, version, relationId, "mention-1", "mention-2", "requires",
                outcome, "entity-1", "entity-2", outcome, "{}", "relation-validation-v1",
                "active", Instant.now(), null, "source-a", revision, 5L, "run-a", "CURRENT");
    }

    private static Row promotionRow(String tenant, UUID version, String relationId) {
        return session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.relation_promotion_results
                WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                """, tenant, version, relationId)).one();
    }

    private static RelationPromotionResultRow rowFor(String tenant, UUID version,
                                                      String relationId, String claimStatus) {
        return new RelationPromotionResultRow(tenant, version, relationId, "mention-1",
                "mention-2", "requires", "VALID", "entity-1", "entity-2", "VALID", "{}",
                "relation-validation-v1", "active", Instant.now(), null, "source-a", version,
                1, "run-654", "CURRENT");
    }

    private static String promotionStatus(String tenant, UUID version, String relationId) {
        Row row = session.execute(SimpleStatement.newInstance("""
                SELECT promotion_status FROM ingestion_cache.relation_promotion_results
                WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                """, tenant, version, relationId)).one();
        return row == null ? null : row.getString("promotion_status");
    }

    private static SimpleStatement rowInsert(String tenant, UUID version, String relationId) {
        return SimpleStatement.newInstance("""
                INSERT INTO ingestion_cache.relation_promotion_results
                    (tenant_id, source_version_id, relation_id, subject_mention_id,
                     object_mention_id, predicate, promotion_status, canonical_subject_id,
                     canonical_object_id, reason, evidence_json, strategy_version,
                     ontology_version, decided_at, claim_status, attempt_count)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenant, version, relationId, "mention-1", "mention-2", "requires",
                "VALID", "entity-1", "entity-2", "VALID", "{}", "relation-validation-v1",
                "active", Instant.now(), "PENDING", 0);
    }

    private static boolean rowExists(String tenant, UUID version, String relationId) {
        Row row = session.execute(SimpleStatement.newInstance("""
                SELECT relation_id FROM ingestion_cache.relation_promotion_results
                WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                """, tenant, version, relationId)).one();
        return row != null;
    }

    private static long sentinelCount(String tenant, UUID version) {
        Row row = session.execute(SimpleStatement.newInstance("""
                SELECT COUNT(*) AS c FROM ingestion_cache.relation_promotion_results
                WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                """, tenant, version, PromotionOwnershipStore.OWNERSHIP_RELATION_ID)).one();
        return row == null ? 0 : row.getLong("c");
    }

    private static long nonSentinelRowCount(String tenant, UUID version) {
        long count = 0;
        for (Row row : session.execute(SimpleStatement.newInstance("""
                SELECT relation_id FROM ingestion_cache.relation_promotion_results
                WHERE tenant_id=? AND source_version_id=?
                """, tenant, version))) {
            if (!PromotionOwnershipStore.OWNERSHIP_RELATION_ID.equals(
                    row.getString("relation_id"))) {
                count++;
            }
        }
        return count;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static List<Throwable> runTogether(ThrowingRunnable first,
                                               ThrowingRunnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        Thread one = new Thread(() -> runAfterStart(first, ready, start, errors));
        Thread two = new Thread(() -> runAfterStart(second, ready, start, errors));
        one.start();
        two.start();
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        one.join(30_000);
        two.join(30_000);
        assertThat(one.isAlive()).isFalse();
        assertThat(two.isAlive()).isFalse();
        return errors;
    }

    private static void runAfterStart(ThrowingRunnable task, CountDownLatch ready,
                                      CountDownLatch start, List<Throwable> errors) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("race start latch timed out");
            }
            task.run();
        } catch (Throwable failure) {
            errors.add(failure);
        }
    }
}
