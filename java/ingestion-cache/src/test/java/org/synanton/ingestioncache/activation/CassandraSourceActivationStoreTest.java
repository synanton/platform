package org.synanton.ingestioncache.activation;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CassandraSourceActivationStoreTest {

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static CassandraSourceActivationStore store;

    @BeforeAll
    static void startCassandra() {
        cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
        cassandra.start();
        InetSocketAddress contact = new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042));
        try (CqlSession admin = CqlSession.builder().addContactPoint(contact)
                .withLocalDatacenter("datacenter1").build()) {
            admin.execute("CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                    + "{'class':'SimpleStrategy','replication_factor':1}");
        }
        session = CqlSession.builder().addContactPoint(contact)
                .withLocalDatacenter("datacenter1").withKeyspace("ingestion_cache").build();
        SchemaInstaller.install(session);
        store = new CassandraSourceActivationStore(session);
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
    void stageIsIdempotentAndDoesNotChangeCurrent() {
        String source = "source-stage";
        ActivationRecord record = record(source, 1, UUID.randomUUID());

        assertThat(store.stage(record)).isTrue();
        assertThat(store.stage(record)).isFalse();
        assertThat(store.readLifecycle("tenant-activation", source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.acceptedGeneration()).isEqualTo(1);
                    assertThat(lifecycle.currentGeneration()).isZero();
                    assertThat(lifecycle.activeRevisionId()).isNull();
                    assertThat(lifecycle.pendingRevisionId()).isEqualTo(record.revisionId());
                    assertThat(lifecycle.lifecycleState()).isEqualTo(ActivationState.REVISION_ACCEPTED);
                });
    }

    @Test
    void committedProjectionsActivateAndReplayIdempotently() {
        String source = "source-activate";
        ActivationRecord record = record(source, 1, UUID.randomUUID());
        store.stage(record);
        advance(record, ActivationState.REVISION_ACCEPTED, ActivationState.EXTRACTING, 0, 0);
        advance(record, ActivationState.EXTRACTING, ActivationState.EXTRACTION_COMPLETE, 0, 0);
        advance(record, ActivationState.EXTRACTION_COMPLETE, ActivationState.GRAPH_COMMITTING, 0, 0);
        advance(record, ActivationState.GRAPH_COMMITTING, ActivationState.MENTIONS_COMMITTED, 1, 0);
        advance(record, ActivationState.MENTIONS_COMMITTED, ActivationState.GRAPH_COMMITTED, 1, 0);
        advance(record, ActivationState.GRAPH_COMMITTED, ActivationState.SEARCH_COMMITTING, 1, 0);
        advance(record, ActivationState.SEARCH_COMMITTING, ActivationState.SEARCH_COMMITTED, 1, 1);

        assertThat(store.activate(record.tenantId(), source, 1, record.revisionId(), Instant.now())).isTrue();
        assertThat(store.readLifecycle(record.tenantId(), source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.currentGeneration()).isEqualTo(1);
                    assertThat(lifecycle.activeRevisionId()).isEqualTo(record.revisionId());
                    assertThat(lifecycle.pendingRevisionId()).isNull();
                    assertThat(lifecycle.lifecycleState()).isEqualTo(ActivationState.CURRENT);
                });
        assertThat(store.activate(record.tenantId(), source, 1, record.revisionId(), Instant.now())).isTrue();
    }

    @Test
    void disallowedAndStaleTransitionsDoNotMutate() {
        String source = "source-reject";
        ActivationRecord record = record(source, 1, UUID.randomUUID());
        store.stage(record);

        assertThatThrownBy(() -> advance(record, ActivationState.REVISION_ACCEPTED,
                ActivationState.GRAPH_COMMITTED, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.transition(record.tenantId(), source, 1,
                ActivationState.EXTRACTING, ActivationState.EXTRACTION_COMPLETE,
                SourceActivationStore.ProjectionMarkers.none(record), null, false, Instant.now()))
                .isFalse();
        assertThat(store.read(record.tenantId(), source, 1)).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.REVISION_ACCEPTED);
    }

    @Test
    void crashAfterCurrentFlipRecoversFromSearchCommitted() {
        String source = "source-crash";
        ActivationRecord record = record(source, 1, UUID.randomUUID());
        store.stage(record);
        advance(record, ActivationState.REVISION_ACCEPTED, ActivationState.EXTRACTING, 0, 0);
        advance(record, ActivationState.EXTRACTING, ActivationState.EXTRACTION_COMPLETE, 0, 0);
        advance(record, ActivationState.EXTRACTION_COMPLETE, ActivationState.GRAPH_COMMITTING, 0, 0);
        advance(record, ActivationState.GRAPH_COMMITTING, ActivationState.MENTIONS_COMMITTED, 1, 0);
        advance(record, ActivationState.MENTIONS_COMMITTED, ActivationState.GRAPH_COMMITTED, 1, 0);
        advance(record, ActivationState.GRAPH_COMMITTED, ActivationState.SEARCH_COMMITTING, 1, 0);
        advance(record, ActivationState.SEARCH_COMMITTING, ActivationState.SEARCH_COMMITTED, 1, 1);

        session.execute("UPDATE source_current SET current_generation=1, active_revision_id="
                + record.revisionId() + ", pending_generation=null, pending_revision_id=null "
                + "WHERE tenant_id='" + record.tenantId() + "' AND source_id='" + source + "'");

        assertThat(store.activate(record.tenantId(), source, 1, record.revisionId(), Instant.now())).isTrue();
        assertThat(store.read(record.tenantId(), source, 1)).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.CURRENT);
    }

    @Test
    void activationPartialCanBeRepairedWithoutResettingGeneration() {
        String source = "source-partial";
        ActivationRecord record = record(source, 1, UUID.randomUUID());
        store.stage(record);
        advance(record, ActivationState.REVISION_ACCEPTED, ActivationState.EXTRACTING, 0, 0);
        advance(record, ActivationState.EXTRACTING, ActivationState.EXTRACTION_COMPLETE, 0, 0);
        advance(record, ActivationState.EXTRACTION_COMPLETE, ActivationState.GRAPH_COMMITTING, 0, 0);
        advance(record, ActivationState.GRAPH_COMMITTING, ActivationState.MENTIONS_COMMITTED, 1, 0);
        advance(record, ActivationState.MENTIONS_COMMITTED, ActivationState.GRAPH_COMMITTED, 1, 0);
        advance(record, ActivationState.GRAPH_COMMITTED, ActivationState.SEARCH_COMMITTING, 1, 0);
        advance(record, ActivationState.SEARCH_COMMITTING, ActivationState.SEARCH_COMMITTED, 1, 1);
        advance(record, ActivationState.SEARCH_COMMITTED, ActivationState.ACTIVATION_PARTIAL,
                1, 1, "current pointer unavailable", true);

        assertThat(store.activate(record.tenantId(), source, 1, record.revisionId(), Instant.now())).isFalse();
        assertThat(store.readLifecycle(record.tenantId(), source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.currentGeneration()).isZero();
                    assertThat(lifecycle.lifecycleState()).isEqualTo(ActivationState.ACTIVATION_PARTIAL);
                });

        advance(record, ActivationState.ACTIVATION_PARTIAL, ActivationState.SEARCH_COMMITTED, 1, 1);
        assertThat(store.activate(record.tenantId(), source, 1, record.revisionId(), Instant.now())).isTrue();
        assertThat(store.readLifecycle(record.tenantId(), source)).get()
                .extracting(SourceLifecycleRecord::currentGeneration).isEqualTo(1L);
    }

    @Test
    void olderGenerationCannotReplaceNewerCurrent() {
        String source = "source-older";
        ActivationRecord gen1 = record(source, 1, UUID.randomUUID());
        ActivationRecord gen2 = record(source, 2, UUID.randomUUID());
        store.stage(gen1);
        store.stage(gen2);
        advance(gen1, ActivationState.REVISION_ACCEPTED, ActivationState.EXTRACTING, 0, 0);
        advance(gen1, ActivationState.EXTRACTING, ActivationState.EXTRACTION_COMPLETE, 0, 0);
        advance(gen1, ActivationState.EXTRACTION_COMPLETE, ActivationState.GRAPH_COMMITTING, 0, 0);
        advance(gen1, ActivationState.GRAPH_COMMITTING, ActivationState.MENTIONS_COMMITTED, 1, 0);
        advance(gen1, ActivationState.MENTIONS_COMMITTED, ActivationState.GRAPH_COMMITTED, 1, 0);
        advance(gen1, ActivationState.GRAPH_COMMITTED, ActivationState.SEARCH_COMMITTING, 1, 0);
        advance(gen1, ActivationState.SEARCH_COMMITTING, ActivationState.SEARCH_COMMITTED, 1, 1);
        advance(gen2, ActivationState.REVISION_ACCEPTED, ActivationState.EXTRACTING, 0, 0);
        advance(gen2, ActivationState.EXTRACTING, ActivationState.EXTRACTION_COMPLETE, 0, 0);
        advance(gen2, ActivationState.EXTRACTION_COMPLETE, ActivationState.GRAPH_COMMITTING, 0, 0);
        advance(gen2, ActivationState.GRAPH_COMMITTING, ActivationState.MENTIONS_COMMITTED, 2, 0);
        advance(gen2, ActivationState.MENTIONS_COMMITTED, ActivationState.GRAPH_COMMITTED, 2, 0);
        advance(gen2, ActivationState.GRAPH_COMMITTED, ActivationState.SEARCH_COMMITTING, 2, 0);
        advance(gen2, ActivationState.SEARCH_COMMITTING, ActivationState.SEARCH_COMMITTED, 2, 2);

        assertThat(store.activate(gen2.tenantId(), source, 2, gen2.revisionId(), Instant.now())).isTrue();
        assertThat(store.activate(gen1.tenantId(), source, 1, gen1.revisionId(), Instant.now())).isFalse();
        assertThat(store.readLifecycle(gen2.tenantId(), source)).get()
                .extracting(SourceLifecycleRecord::currentGeneration).isEqualTo(2L);
    }

    private static void advance(ActivationRecord record, ActivationState expected,
                                ActivationState next, long graphGeneration, long searchGeneration) {
        advance(record, expected, next, graphGeneration, searchGeneration, null, false);
    }

    private static void advance(ActivationRecord record, ActivationState expected,
                                ActivationState next, long graphGeneration, long searchGeneration,
                                String error, boolean retryEligible) {
        assertThat(store.transition(record.tenantId(), record.sourceId(), record.generation(),
                expected, next, new SourceActivationStore.ProjectionMarkers(
                        graphGeneration, graphGeneration == 0 ? null : "COMMITTED",
                        searchGeneration, searchGeneration == 0 ? null : "COMMITTED"),
                error, retryEligible, Instant.now())).isTrue();
    }

    private static ActivationRecord record(String source, long generation, UUID revision) {
        Instant now = Instant.now();
        return new ActivationRecord("tenant-activation", source, generation, revision,
                "connector", "resource", null, ActivationState.REVISION_ACCEPTED,
                "hash-" + generation, 0, null, 0, null, null, false, now, now, null);
    }
}
