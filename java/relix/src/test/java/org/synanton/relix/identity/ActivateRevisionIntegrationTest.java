package org.synanton.relix.identity;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.synanton.ingestioncache.activation.ActivationRecord;
import org.synanton.ingestioncache.activation.ActivationState;
import org.synanton.ingestioncache.activation.CassandraSourceActivationStore;
import org.synanton.ingestioncache.activation.SourceActivationStore;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.relix.adapter.out.graph.neo4j.DriverCypherExecutor;
import org.synanton.relix.adapter.out.metadata.Neo4jSourceFenceStore;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActivateRevisionIntegrationTest {

    private static CassandraContainer cassandra;
    private static Neo4jContainer<?> neo4j;
    private static CqlSession session;
    private static Driver driver;
    private static CassandraSourceActivationStore activations;
    private static Neo4jSourceFenceStore fences;

    @BeforeAll
    static void startStores() {
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
        activations = new CassandraSourceActivationStore(session);

        neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26")).withoutAuthentication();
        neo4j.start();
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.none());
        fences = new Neo4jSourceFenceStore(new DriverCypherExecutor(driver, "neo4j"));
    }

    @AfterAll
    static void stopStores() {
        if (driver != null) {
            driver.close();
        }
        if (neo4j != null) {
            neo4j.stop();
        }
        if (session != null) {
            session.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @Test
    void activationRequiresSearchCommitThenIsIdempotent() {
        String tenant = "tenant-activate-revision";
        String source = "source-activate-revision";
        ActivationRecord record = record(tenant, source, 1, UUID.randomUUID());
        activations.stage(record);
        advanceToGraphCommitted(record);
        fences.advance(new SourceFenceKey(tenant, source), 1);

        ActivateRevision service = new ActivateRevision(activations, fences);
        ActivateRevision.Result notReady = service.activate(request(record));
        assertThat(notReady.status()).isEqualTo(ActivateRevision.Status.NOT_READY);
        assertThat(notReady.currentGeneration()).isZero();

        advanceToSearchCommitted(record);
        ActivateRevision.Result activated = service.activate(request(record));
        assertThat(activated.status()).isEqualTo(ActivateRevision.Status.ACTIVATED);
        assertThat(activated.currentGeneration()).isEqualTo(1);
        assertThat(activations.readLifecycle(tenant, source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.currentGeneration()).isEqualTo(1);
                    assertThat(lifecycle.activeRevisionId()).isEqualTo(record.revisionId());
                    assertThat(lifecycle.lifecycleState()).isEqualTo(ActivationState.CURRENT);
                });

        ActivateRevision.Result replay = service.activate(request(record));
        assertThat(replay.status()).isEqualTo(ActivateRevision.Status.ALREADY_CURRENT);
        assertThat(replay.currentGeneration()).isEqualTo(1);
    }

    @Test
    void fenceAdvancedPastGenerationRejectsActivationWithoutChangingCurrent() {
        String tenant = "tenant-activate-stale";
        String source = "source-activate-stale";
        ActivationRecord record = record(tenant, source, 2, UUID.randomUUID());
        activations.stage(record);
        advanceToSearchCommitted(record);
        fences.advance(new SourceFenceKey(tenant, source), 3);

        ActivateRevision.Result result = new ActivateRevision(activations, fences).activate(request(record));

        assertThat(result.status()).isEqualTo(ActivateRevision.Status.STALE_GENERATION);
        assertThat(result.currentGeneration()).isZero();
        assertThat(activations.readLifecycle(tenant, source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.currentGeneration()).isZero();
                    assertThat(lifecycle.lifecycleState()).isEqualTo(ActivationState.STALE_REJECTED);
                });
    }

    @Test
    void searchCommittedCrashRecoversByRepairingCurrentPointer() {
        String tenant = "tenant-activate-crash";
        String source = "source-activate-crash";
        UUID revision = UUID.randomUUID();
        ActivationRecord record = record(tenant, source, 1, revision);
        activations.stage(record);
        advanceToSearchCommitted(record);
        fences.advance(new SourceFenceKey(tenant, source), 1);

        // Crash after current flip, before activation-record state flip.
        session.execute("UPDATE source_current SET current_generation=1, active_revision_id="
                + revision + ", pending_generation=null, pending_revision_id=null "
                + "WHERE tenant_id='" + tenant + "' AND source_id='" + source + "'");
        assertThat(activations.read(tenant, source, 1)).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.SEARCH_COMMITTED);

        ActivateRevision.Result result = new ActivateRevision(activations, fences).activate(request(record));

        assertThat(result.status()).isEqualTo(ActivateRevision.Status.ACTIVATED);
        assertThat(activations.read(tenant, source, 1)).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.CURRENT);
    }

    @Test
    void activationFailureIsPartialAndRepairable() {
        String tenant = "tenant-activate-partial";
        String source = "source-activate-partial";
        ActivationRecord record = record(tenant, source, 1, UUID.randomUUID());
        activations.stage(record);
        advanceToSearchCommitted(record);
        fences.advance(new SourceFenceKey(tenant, source), 1);

        SourceActivationStore failing = new DelegatingActivationStore(activations) {
            @Override
            public boolean activate(String tenantId, String sourceId, long generation,
                                    UUID revisionId, Instant now) {
                return false;
            }
        };
        ActivateRevision.Result partial = new ActivateRevision(failing, fences).activate(request(record));
        assertThat(partial.status()).isEqualTo(ActivateRevision.Status.ACTIVATION_PARTIAL);
        assertThat(partial.retryEligible()).isTrue();
        assertThat(activations.readLifecycle(tenant, source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.currentGeneration()).isZero();
                    assertThat(lifecycle.lifecycleState()).isEqualTo(ActivationState.ACTIVATION_PARTIAL);
                });

        ActivateRevision.Result repaired = new ActivateRevision(activations, fences).activate(request(record));
        assertThat(repaired.status()).isEqualTo(ActivateRevision.Status.ACTIVATED);
        assertThat(activations.readLifecycle(tenant, source)).get()
                .extracting(lifecycle -> lifecycle.currentGeneration()).isEqualTo(1L);
    }

    private static void advanceToGraphCommitted(ActivationRecord record) {
        transition(record, ActivationState.REVISION_ACCEPTED, ActivationState.EXTRACTING, 0, 0);
        transition(record, ActivationState.EXTRACTING, ActivationState.EXTRACTION_COMPLETE, 0, 0);
        transition(record, ActivationState.EXTRACTION_COMPLETE, ActivationState.GRAPH_COMMITTING, 0, 0);
        transition(record, ActivationState.GRAPH_COMMITTING, ActivationState.GRAPH_COMMITTED,
                record.generation(), 0);
    }

    private static void advanceToSearchCommitted(ActivationRecord record) {
        if (activations.read(record.tenantId(), record.sourceId(), record.generation())
                .map(ActivationRecord::state).orElse(null) == ActivationState.REVISION_ACCEPTED) {
            advanceToGraphCommitted(record);
        }
        transition(record, ActivationState.GRAPH_COMMITTED, ActivationState.SEARCH_COMMITTING,
                record.generation(), 0);
        transition(record, ActivationState.SEARCH_COMMITTING, ActivationState.SEARCH_COMMITTED,
                record.generation(), record.generation());
    }

    private static void transition(ActivationRecord record, ActivationState expected,
                                   ActivationState next, long graphGeneration, long searchGeneration) {
        assertThat(activations.transition(record.tenantId(), record.sourceId(), record.generation(),
                expected, next, new SourceActivationStore.ProjectionMarkers(
                        graphGeneration, graphGeneration == 0 ? null : "COMMITTED",
                        searchGeneration, searchGeneration == 0 ? null : "COMMITTED"),
                null, false, Instant.now())).isTrue();
    }

    private static ActivateRevision.Request request(ActivationRecord record) {
        return new ActivateRevision.Request(record.tenantId(), record.sourceId(),
                record.generation(), record.revisionId());
    }

    private static ActivationRecord record(String tenant, String source, long generation, UUID revision) {
        Instant now = Instant.now();
        return new ActivationRecord(tenant, source, generation, revision,
                "connector", "resource", null, ActivationState.REVISION_ACCEPTED,
                "hash-" + generation, 0, null, 0, null, null, false, now, now, null);
    }

    private static class DelegatingActivationStore implements SourceActivationStore {
        private final SourceActivationStore delegate;

        private DelegatingActivationStore(SourceActivationStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean stage(ActivationRecord record) {
            return delegate.stage(record);
        }

        @Override
        public Optional<ActivationRecord> read(String tenantId, String sourceId, long generation) {
            return delegate.read(tenantId, sourceId, generation);
        }

        @Override
        public Optional<ActivationRecord> readLatest(String tenantId, String sourceId) {
            return delegate.readLatest(tenantId, sourceId);
        }

        @Override
        public Optional<org.synanton.ingestioncache.activation.SourceLifecycleRecord> readLifecycle(
                String tenantId, String sourceId) {
            return delegate.readLifecycle(tenantId, sourceId);
        }

        @Override
        public boolean transition(String tenantId, String sourceId, long generation,
                                  ActivationState expected, ActivationState next,
                                  ProjectionMarkers markers, String error,
                                  boolean retryEligible, Instant now) {
            return delegate.transition(tenantId, sourceId, generation, expected, next,
                    markers, error, retryEligible, now);
        }

        @Override
        public boolean activate(String tenantId, String sourceId, long generation,
                                UUID revisionId, Instant now) {
            return delegate.activate(tenantId, sourceId, generation, revisionId, now);
        }
    }
}
