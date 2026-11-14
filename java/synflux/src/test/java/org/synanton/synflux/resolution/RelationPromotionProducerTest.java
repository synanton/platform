package org.synanton.synflux.resolution;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.synanton.common.resolution.ActiveResolution;
import org.synanton.common.resolution.ExtractedMentionGraphReadPort;
import org.synanton.common.resolution.ExtractedRelationView;
import org.synanton.common.resolution.RelationValidationOutcome;
import org.synanton.common.resolution.RelationValidationResult;
import org.synanton.common.resolution.ResolutionGraphLookup;
import org.synanton.common.resolution.StaleOwnershipException;
import org.synanton.ingestioncache.claim.PromotionOwnershipStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationPromotionProducerTest {

    private static final String TENANT = "tenant-producer-ownership";
    private static final String ONTOLOGY_VERSION = "1.0.0";
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static PromotionOwnershipStore ownership;

    private final ResolutionGraphLookup graph = mock(ResolutionGraphLookup.class);
    private final ExtractedMentionGraphReadPort extracted = mock(ExtractedMentionGraphReadPort.class);
    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

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
        ownership = new PromotionOwnershipStore(session);
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
    void competingProducersOnlyOneValidatesAndInsertsRows() throws Exception {
        UUID version = UUID.randomUUID();
        List<ExtractedRelationView> relations = List.of(
                relation(version, "relation-a"), relation(version, "relation-b"));
        when(cache.listMentionsCommittedSourceVersions(eq(TENANT), anyInt()))
                .thenReturn(List.of(sourceVersion(version)));
        when(extracted.listExtractedRelations(eq(TENANT), eq(version.toString()))).thenReturn(relations);
        when(graph.getActiveResolutions(eq(TENANT), anyList())).thenReturn(List.of(
                new ActiveResolution("m-subject", "e-subject", "MaintenanceSystem"),
                new ActiveResolution("m-object", "e-object", "Protocol")));
        when(cache.listRelationPromotionResults(eq(TENANT), eq(version))).thenReturn(List.of());
        DefaultCanonicalRelationValidator validatorA = validator();
        DefaultCanonicalRelationValidator validatorB = validator();
        RelationPromotionProducer producerA = producer(validatorA, "producer-a");
        RelationPromotionProducer producerB = producer(validatorB, "producer-b");

        CountDownLatch start = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            await(start);
            producerA.poll();
        });
        Thread second = new Thread(() -> {
            await(start);
            producerB.poll();
        });
        first.start();
        second.start();
        start.countDown();
        first.join(30_000);
        second.join(30_000);

        long callsA = validateCalls(validatorA);
        long callsB = validateCalls(validatorB);
        assertThat(callsA + callsB).isEqualTo(2);
        assertThat(callsA == 2 ^ callsB == 2).isTrue();
        assertThat(nonSentinelRowCount(version)).isEqualTo(2);
        assertThat(ownership.isClosed(TENANT, version)).isTrue();
    }

    @Test
    void producerClosesAfterAllRowsAndClosedTokenInsertFails() {
        UUID version = UUID.randomUUID();
        ExtractedRelationView existing = relation(version, "relation-existing");
        ExtractedRelationView pending = relation(version, "relation-pending");
        PromotionOwnershipStore.OwnershipToken token =
                ownership.acquire(TENANT, version, "producer-close").orElseThrow();
        ownership.insertPromotionResult(row(existing), token);
        when(cache.listMentionsCommittedSourceVersions(eq(TENANT), anyInt()))
                .thenReturn(List.of(sourceVersion(version)));
        when(extracted.listExtractedRelations(eq(TENANT), eq(version.toString())))
                .thenReturn(List.of(existing, pending));
        when(graph.getActiveResolutions(eq(TENANT), anyList())).thenReturn(List.of(
                new ActiveResolution("m-subject", "e-subject", "MaintenanceSystem"),
                new ActiveResolution("m-object", "e-object", "Protocol")));
        when(cache.listRelationPromotionResults(eq(TENANT), eq(version)))
                .thenReturn(List.of(row(existing)));

        producer(validator(), "producer-close").poll();

        assertThat(ownership.isClosed(TENANT, version)).isTrue();
        assertThat(nonSentinelRowCount(version)).isEqualTo(2);
        assertThatThrownBy(() -> ownership.insertPromotionResult(
                row(relation(version, "relation-after-close")), token))
                .isInstanceOf(StaleOwnershipException.class);
        assertThat(nonSentinelRowCount(version)).isEqualTo(2);
    }

    @Test
    void partialInsertFailureDoesNotCloseAndTakeoverCompletesWithoutDuplicates() {
        UUID version = UUID.randomUUID();
        var relations = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(i -> relation(version, "relation-partial-" + i)).toList();
        when(cache.listMentionsCommittedSourceVersions(eq(TENANT), anyInt()))
                .thenReturn(List.of(sourceVersion(version)));
        when(extracted.listExtractedRelations(eq(TENANT), eq(version.toString())))
                .thenReturn(relations);
        when(graph.getActiveResolutions(eq(TENANT), anyList())).thenReturn(List.of(
                new ActiveResolution("m-subject", "e-subject", "MaintenanceSystem"),
                new ActiveResolution("m-object", "e-object", "Protocol")));
        when(cache.listRelationPromotionResults(eq(TENANT), eq(version)))
                .thenReturn(List.of(), relations.subList(0, 3).stream().map(
                        RelationPromotionProducerTest::row).toList());

        AtomicInteger calls = new AtomicInteger();
        DefaultCanonicalRelationValidator failing = mock(DefaultCanonicalRelationValidator.class);
        when(failing.validate(eq(TENANT), eq(ONTOLOGY_VERSION), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() >= 4) {
                throw new IllegalStateException("injected validation/insertion failure");
            }
            return new RelationValidationResult(RelationValidationOutcome.VALID, "e-subject",
                    "requires", "e-object", "MaintenanceSystem", "Protocol",
                    ONTOLOGY_VERSION, List.of(), "VALID");
        });

        producer(failing, "producer-partial").poll();

        PromotionOwnershipStore.OwnershipRow open = ownership.read(TENANT, version).orElseThrow();
        assertThat(open.closed()).isFalse();
        assertThat(open.ownerGeneration()).isEqualTo(1);
        assertThat(nonSentinelRowCount(version)).isEqualTo(3);

        PromotionOwnershipStore.OwnershipToken takeover =
                ownership.takeover(TENANT, version, "producer-recovery", 1).orElseThrow();
        assertThat(takeover.generation()).isEqualTo(2);
        producer(validator(), "producer-recovery").poll();

        PromotionOwnershipStore.OwnershipRow closed = ownership.read(TENANT, version).orElseThrow();
        assertThat(closed.closed()).isTrue();
        assertThat(closed.ownerGeneration()).isEqualTo(2);
        assertThat(nonSentinelRowCount(version)).isEqualTo(5);
    }

    @Test
    void zeroRowRevisionClosesOnceAndRestartDoesNotAllocateNewGeneration() {
        UUID version = UUID.randomUUID();
        when(cache.listMentionsCommittedSourceVersions(eq(TENANT), anyInt()))
                .thenReturn(List.of(sourceVersion(version)));
        when(extracted.listExtractedRelations(eq(TENANT), eq(version.toString())))
                .thenReturn(List.of());
        when(cache.listRelationPromotionResults(eq(TENANT), eq(version))).thenReturn(List.of());

        RelationPromotionProducer producer = producer(validator(), "producer-zero");
        producer.poll();

        PromotionOwnershipStore.OwnershipRow closed = ownership.read(TENANT, version).orElseThrow();
        assertThat(closed.closed()).isTrue();
        assertThat(closed.ownerGeneration()).isEqualTo(1);
        Instant closedAt = closed.closedAt();
        assertThat(nonSentinelRowCount(version)).isZero();

        producer.poll();
        PromotionOwnershipStore.OwnershipRow afterRestart = ownership.read(TENANT, version).orElseThrow();
        assertThat(afterRestart.ownerGeneration()).isEqualTo(1);
        assertThat(afterRestart.closedAt()).isEqualTo(closedAt);
        assertThat(nonSentinelRowCount(version)).isZero();
    }

    private RelationPromotionProducer producer(DefaultCanonicalRelationValidator validator,
                                               String producerId) {
        return new RelationPromotionProducer(graph, extracted, validator, cache, ownership, clock,
                true, TENANT, ONTOLOGY_VERSION, producerId);
    }

    private static DefaultCanonicalRelationValidator validator() {
        DefaultCanonicalRelationValidator validator = mock(DefaultCanonicalRelationValidator.class);
        when(validator.validate(eq(TENANT), eq(ONTOLOGY_VERSION), any())).thenReturn(
                new RelationValidationResult(RelationValidationOutcome.VALID, "e-subject",
                        "requires", "e-object", "MaintenanceSystem", "Protocol",
                        ONTOLOGY_VERSION, List.of(), "VALID"));
        return validator;
    }

    private static long validateCalls(DefaultCanonicalRelationValidator validator) {
        return Mockito.mockingDetails(validator).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("validate"))
                .count();
    }

    private static SourceVersionRow sourceVersion(UUID version) {
        return new SourceVersionRow(TENANT, "connector", "source", "resource", version,
                "sha", "rev", NOW, UUID.randomUUID(), UUID.randomUUID(), "", 5, "hash", null);
    }

    private static ExtractedRelationView relation(UUID version, String relationId) {
        return new ExtractedRelationView(relationId, TENANT, version.toString(),
                "m-subject", "m-object", "requires", UUID.randomUUID() + ":0", 0, 20,
                "source-a", version.toString(), 5, "run-a");
    }

    private static RelationPromotionResultRow row(ExtractedRelationView view) {
        return new RelationPromotionResultRow(TENANT, UUID.fromString(view.sourceVersionId()),
                view.relationId(), view.subjectMentionId(), view.objectMentionId(),
                view.predicate(), "VALID", "e-subject", "e-object", "VALID", "{}",
                DefaultCanonicalRelationValidator.STRATEGY_VERSION, ONTOLOGY_VERSION, NOW,
                null, view.sourceId(), UUID.fromString(view.revisionId()), view.generation(),
                view.extractionRunId(), "CURRENT");
    }

    private static long nonSentinelRowCount(UUID version) {
        long count = 0;
        for (Row row : session.execute(SimpleStatement.newInstance("""
                SELECT relation_id FROM ingestion_cache.relation_promotion_results
                WHERE tenant_id=? AND source_version_id=?
                """, TENANT, version))) {
            if (!PromotionOwnershipStore.OWNERSHIP_RELATION_ID.equals(
                    row.getString("relation_id"))) {
                count++;
            }
        }
        return count;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
