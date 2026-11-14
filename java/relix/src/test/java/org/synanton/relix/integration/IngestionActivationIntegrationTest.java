package org.synanton.relix.integration;

import com.datastax.oss.driver.api.core.CqlSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import org.synanton.ingestioncache.activation.ActivationRecord;
import org.synanton.ingestioncache.activation.ActivationState;
import org.synanton.ingestioncache.activation.CassandraSourceActivationStore;
import org.synanton.ingestioncache.activation.SourceActivationStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.ActivationRequestRow;
import org.synanton.ingestioncache.domain.ExtractionRequestRow;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.relix.adapter.out.graph.neo4j.DriverCypherExecutor;
import org.synanton.relix.adapter.out.graph.neo4j.Neo4jGraphConnector;
import org.synanton.relix.adapter.out.metadata.CassandraSourceGenerationAuthority;
import org.synanton.relix.adapter.out.metadata.Neo4jSourceFenceStore;
import org.synanton.relix.config.ActivationRecoveryProperties;
import org.synanton.relix.config.PromotionConsumerProperties;
import org.synanton.relix.consumer.ActivationDriftMonitor;
import org.synanton.relix.consumer.ActivationRecoverySweep;
import org.synanton.relix.identity.ActivateRevision;
import org.synanton.relix.identity.SourceFenceKey;
import org.synanton.relix.identity.SourceFenceReconciler;
import org.synanton.relix.identity.SourceFenceStatus;
import org.synanton.relix.identity.SourceFenceStore;
import org.synanton.synflux.events.IngestionEventConfig;
import org.synanton.synflux.lifecycle.FixtureIngestionService;
import org.synanton.synflux.lifecycle.VerifiedIngestionContext;
import org.synanton.synvault.domain.ContentRef;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #654 real-store lifecycle: acceptance, durable graph-commit event, activation
 * recovery, cross-store fence race, and the ingestion-to-current headline path.
 */
class IngestionActivationIntegrationTest {

    private static CassandraContainer cassandra;
    private static Neo4jContainer<?> neo4j;
    private static CqlSession session;
    private static Driver driver;
    private static IngestionCacheClient cache;
    private static CassandraSourceActivationStore activations;
    private static Neo4jSourceFenceStore fences;
    private static DriverCypherExecutor executor;
    private static FixtureIngestionService ingestion;

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
        cache = new IngestionCacheClient(session);
        activations = new CassandraSourceActivationStore(session);
        ingestion = new FixtureIngestionService(cache, Clock.systemUTC(),
                new IngestionEventConfig().ingestionEventTypes());

        neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26")).withoutAuthentication();
        neo4j.start();
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.none());
        executor = new DriverCypherExecutor(driver, "neo4j");
        fences = new Neo4jSourceFenceStore(executor);
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
    void duplicateAcceptanceAllocatesOneGenerationAndOneExtractionRequest() {
        String tenant = "tenant-654-duplicate";
        String source = "source-654-duplicate";
        String resource = "duplicate.md";
        ContentRef ref = ref(resource, "same-content");
        byte[] bytes = "same-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        ingest(tenant, source, resource, ref, bytes);
        ingest(tenant, source, resource, ref, bytes);

        List<SourceVersionRow> versions = cache.listSourceVersions(tenant, "file", source, resource);
        assertThat(versions).hasSize(1);
        SourceVersionRow version = versions.getFirst();
        assertThat(version.generation()).isPositive();
        assertThat(cache.readRevisionGeneration(tenant, source, version.versionId()))
                .contains(version.generation());
        assertThat(cache.listExtractionOutbox(tenant, "file", source, resource)).hasSize(1);
        assertThat(cache.listSourceVersions(tenant, "file", source, resource).stream()
                .map(SourceVersionRow::generation).distinct()).hasSize(1);

        ActivationRecord staged = activations.read(tenant, source, version.generation()).orElseThrow();
        assertThat(staged.state()).isEqualTo(ActivationState.REVISION_ACCEPTED);
        assertThat(staged.revisionId()).isEqualTo(version.versionId());
    }

    @Test
    void graphCommitLwtIsConditionalAndDoesNotDuplicateOrReplaceActivationRequested() {
        String tenant = "tenant-654-graph-lwt";
        String source = "source-654-graph-lwt";
        String resource = "graph.md";
        SourceVersionRow version = ingest(tenant, source, resource, ref(resource, "graph"), bytes("graph"));
        ExtractionRequestRow request = request(tenant, version);
        commitGraph(request, version);

        UUID originalEvent = firstActivationEventId(tenant, source, resource);
        assertThat(cache.listPendingActivationRequests(tenant, 10, Instant.now())).hasSize(1);

        boolean repeated = cache.markGraphCommitted(request, "GRAPH_COMMITTED", Map.of(),
                0, Instant.now(), UUID.randomUUID(), "ActivationRequested", "{}");
        assertThat(repeated).isFalse();
        assertThat(firstActivationEventId(tenant, source, resource)).isEqualTo(originalEvent);
        assertThat(cache.listPendingActivationRequests(tenant, 10, Instant.now())).hasSize(1);
    }

    @Test
    void duplicateActivationDeliveryActivatesOnce() {
        String tenant = "tenant-654-duplicate-activation";
        String source = "source-654-duplicate-activation";
        String resource = "activation.md";
        SourceVersionRow version = ingest(tenant, source, resource, ref(resource, "activation"),
                bytes("activation"));
        commitGraph(request(tenant, version), version);
        markSearchProjected(tenant, source, resource, version);
        fences.advance(new SourceFenceKey(tenant, source), version.generation());

        ActivationRecoverySweep sweep = sweep(tenant);
        sweep.poll();
        ActivationRequestRow pending = cache.listPendingActivationRequests(tenant, 10, Instant.now())
                .stream().findFirst().orElse(null);
        assertThat(pending).isNull();
        ActivationRecord current = activations.read(tenant, source, version.generation()).orElseThrow();
        assertThat(current.state()).isEqualTo(ActivationState.CURRENT);

        sweep.sweep(new ActivationRequestRow(tenant, "file", source, resource,
                firstActivationEventId(tenant, source, resource), version.versionId(),
                version.generation(), version.contentHash(), null, "{}", version.observedAt(),
                Instant.now(), "GRAPH_COMMITTED", 0, null, null, false));

        assertThat(activations.read(tenant, source, version.generation())).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.CURRENT);
        assertThat(activations.readLifecycle(tenant, source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.currentGeneration()).isEqualTo(version.generation());
                    assertThat(lifecycle.activeRevisionId()).isEqualTo(version.versionId());
                });
    }

    @Test
    void interruptedActivationAtSearchCommittedIsCompletedBySweepWithoutNewGeneration() {
        String tenant = "tenant-654-interrupted";
        String source = "source-654-interrupted";
        String resource = "interrupted.md";
        SourceVersionRow version = ingest(tenant, source, resource, ref(resource, "interrupted"),
                bytes("interrupted"));
        commitGraph(request(tenant, version), version);
        markSearchProjected(tenant, source, resource, version);
        fences.advance(new SourceFenceKey(tenant, source), version.generation());

        ActivationRecord record = activations.read(tenant, source, version.generation()).orElseThrow();
        advanceToSearchCommitted(record);

        assertThat(activations.readLifecycle(tenant, source)).get()
                .extracting(lifecycle -> lifecycle.currentGeneration()).isEqualTo(0L);

        ActivationRecoverySweep sweep = sweep(tenant);
        sweep.poll();

        assertThat(activations.readLifecycle(tenant, source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.currentGeneration()).isEqualTo(version.generation());
                    assertThat(lifecycle.acceptedGeneration()).isEqualTo(version.generation());
                    assertThat(lifecycle.activeRevisionId()).isEqualTo(version.versionId());
                });
        assertThat(cache.readRevisionGeneration(tenant, source, version.versionId()))
                .contains(version.generation());
        assertThat(rowCount("source_activation", tenant, source)).isEqualTo(1);
        assertThat(cache.listSourceVersions(tenant, "file", source, resource)).hasSize(1);

        sweep.poll();
        assertThat(activations.readLifecycle(tenant, source)).get()
                .extracting(lifecycle -> lifecycle.currentGeneration()).isEqualTo(version.generation());
        assertThat(rowCount("source_activation", tenant, source)).isEqualTo(1);
    }

    @Test
    void ingestionToCurrentHeadlineUsesFixtureStoresGraphCommitAndSweep() {
        String tenant = "tenant-654-headline";
        String source = "source-654-headline";
        String resource = "headline.md";
        SourceVersionRow version = ingest(tenant, source, resource, ref(resource, "headline"),
                bytes("headline"));

        assertThat(activations.readLifecycle(tenant, source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.acceptedGeneration()).isEqualTo(version.generation());
                    assertThat(lifecycle.currentGeneration()).isZero();
                    assertThat(lifecycle.lifecycleState()).isEqualTo(ActivationState.REVISION_ACCEPTED);
                });

        commitGraph(request(tenant, version), version);
        assertThat(activations.read(tenant, source, version.generation())).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.GRAPH_COMMITTED);
        markSearchProjected(tenant, source, resource, version);
        fences.advance(new SourceFenceKey(tenant, source), version.generation());

        ActivationRecoverySweep sweep = sweep(tenant);
        sweep.poll();

        assertThat(activations.readLifecycle(tenant, source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.currentGeneration()).isEqualTo(version.generation());
                    assertThat(lifecycle.activeRevisionId()).isEqualTo(version.versionId());
                    assertThat(lifecycle.lifecycleState()).isEqualTo(ActivationState.CURRENT);
                });
        assertThat(cache.listPendingActivationRequests(tenant, 10, Instant.now())).isEmpty();
        assertThat(cache.readRevisionGeneration(tenant, source, version.versionId()))
                .contains(version.generation());
    }

    @Test
    void fenceAdvanceAfterActivationPreconditionReadStillActivatesOperationGeneration() {
        String tenant = "tenant-654-race";
        String source = "source-654-race";
        String resource = "race.md";
        UUID revision = UUID.randomUUID();
        Instant now = Instant.now();
        cache.createSourceIfAbsent(new SourceRow(tenant, source, "file", "file", "",
                Set.of(), "ACTIVE", now, null));
        ActivationRecord record = new ActivationRecord(tenant, source, 1, revision,
                "file", resource, null, ActivationState.REVISION_ACCEPTED,
                "hash", 0, null, 0, null, null, true, now, now, null);
        activations.stage(record);
        advanceToSearchCommitted(record);
        fences.advance(new SourceFenceKey(tenant, source), 1);
        session.execute("UPDATE source_current SET accepted_generation=1 "
                + "WHERE tenant_id=? AND source_id=?", tenant, source);

        SourceFenceStore racing = new SourceFenceStore() {
            private boolean first = true;

            @Override
            public Optional<Long> read(SourceFenceKey key) {
                Optional<Long> value = fences.read(key);
                if (first) {
                    first = false;
                    session.execute("UPDATE source_current SET accepted_generation=2 "
                            + "WHERE tenant_id=? AND source_id=?", tenant, source);
                    fences.advance(key, 2);
                }
                return value;
            }

            @Override
            public org.synanton.relix.identity.SourceFenceAdvance advance(
                    SourceFenceKey key, long generation) {
                return fences.advance(key, generation);
            }
        };

        ActivateRevision.Result result = new ActivateRevision(activations, racing)
                .activate(new ActivateRevision.Request(tenant, source, 1, revision));

        assertThat(result.status()).isEqualTo(ActivateRevision.Status.ACTIVATED);
        assertThat(result.currentGeneration()).isEqualTo(1);
        assertThat(fences.read(new SourceFenceKey(tenant, source))).contains(2L);
        assertThat(activations.readLifecycle(tenant, source)).get()
                .satisfies(lifecycle -> {
                    assertThat(lifecycle.currentGeneration()).isEqualTo(1);
                    assertThat(lifecycle.acceptedGeneration()).isEqualTo(2);
                });

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ActivationDriftMonitor(cache, activations,
                new PromotionConsumerProperties(true, tenant, 5, 5, 30, 120, 10_000L),
                registry).poll();
        assertThat(registry.get("activation.generation_drift").gauge().value()).isEqualTo(1.0d);

        Neo4jGraphConnector graph = new Neo4jGraphConnector(null, executor);
        CanonicalRelation stale = new CanonicalRelation(tenant, revision.toString(), "relation-1",
                "mention-1", "entity-1", "mention-2", "entity-2", "requires", "1.0",
                revision + ":0", 0, 1, source, revision.toString(), 1, null);
        assertThatThrownBy(() -> graph.promoteRelation(GraphIdentity.canonical(tenant, "v1"),
                stale, new SourceRevisionIdentity(tenant, source, 1)))
                .isInstanceOf(StaleGenerationException.class);

        SourceFenceReconciler reconciler = new SourceFenceReconciler(
                new CassandraSourceGenerationAuthority(cache), fences);
        assertThat(reconciler.reconcile(new SourceFenceKey(tenant, source)).status())
                .isEqualTo(SourceFenceStatus.CONVERGED);
    }

    @Test
    void graphFailureLeavesNoEvidenceAndNoActivationRequested() {
        String tenant = "tenant-654-scenario-c";
        String source = "source-654-scenario-c";
        String resource = "rollback.md";
        SourceVersionRow version = ingest(tenant, source, resource, ref(resource, "rollback"),
                bytes("rollback"));
        ExtractionRequestRow request = request(tenant, version);
        assertThat(cache.claimGraphCommit(request)).isTrue();
        assertThatThrownBy(() -> executor.writeReturning(
                "CREATE (n:RollbackProbe {tenant_id: $tenant}) SET n.value = 'staged'",
                Map.of("tenant", tenant),
                () -> {
                    throw new IllegalStateException("neo4j transaction abort");
                })).isInstanceOf(IllegalStateException.class);
        assertThat(executor.read("MATCH (n:RollbackProbe {tenant_id: $tenant}) "
                + "RETURN count(n) AS c", Map.of("tenant", tenant)).getFirst().get("c"))
                .isEqualTo(0L);
        cache.markGraphFailed(request, "FAILED", Map.of("mentions_accepted", 0), 1,
                Instant.now().plusSeconds(30), Instant.now());

        assertThat(cache.readGraphStatus(tenant, "file", source, resource, version.versionId()))
                .contains("FAILED");
        assertThat(cache.listPendingActivationRequests(tenant, 10, Instant.now())).isEmpty();
        assertThat(firstActivationEventIdOrNull(tenant, source, resource)).isNull();
    }

    private static ActivationRecoverySweep sweep(String tenant) {
        return new ActivationRecoverySweep(cache, activations,
                new ActivateRevision(activations, fences),
                new ActivationRecoveryProperties(true, tenant, 100, 30_000L, 5, 1L),
                new SimpleMeterRegistry(), Clock.systemUTC());
    }

    private static SourceVersionRow ingest(String tenant, String source, String resource,
                                           ContentRef ref, byte[] bytes) {
        ingestion.ingest(new VerifiedIngestionContext(tenant, "integration", "SERVICE"),
                UUID.randomUUID(), UUID.randomUUID(), "file", source, resource, ref, bytes,
                "test-content-reference", (ignored, versionId) ->
                        new FixtureIngestionService.PublishedContent(UUID.randomUUID()));
        return cache.listSourceVersions(tenant, "file", source, resource).getFirst();
    }

    private static ContentRef ref(String resource, String content) {
        return new ContentRef("file", "file:///" + resource, "text/markdown",
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, Instant.now());
    }

    private static byte[] bytes(String content) {
        return content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ExtractionRequestRow request(String tenant, SourceVersionRow version) {
        return new ExtractionRequestRow(tenant, version.connectorId(), version.sourceId(),
                version.resourceId(), UUID.randomUUID(), version.versionId(),
                version.contentRefId(), "{}", version.observedAt(), null,
                "KNOWLEDGE_READY", 0, null, null, false, version.generation());
    }

    private static void commitGraph(ExtractionRequestRow request, SourceVersionRow version) {
        assertThat(cache.claimGraphCommit(request)).isTrue();
        UUID eventId = UUID.nameUUIDFromBytes((request.tenantId() + "|" + request.sourceId()
                + "|" + request.versionId() + "|" + request.generation())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(cache.markGraphCommitted(request, "GRAPH_COMMITTED", Map.of(), 0,
                Instant.now(), eventId, "ActivationRequested",
                "{\"generation\":" + version.generation() + "}")).isTrue();
        advanceActivationToGraphCommitted(request);
    }

    private static void advanceActivationToGraphCommitted(ExtractionRequestRow request) {
        ActivationRecord record = activations.read(request.tenantId(), request.sourceId(),
                request.generation()).orElseThrow();
        if (record.state() == ActivationState.REVISION_ACCEPTED) {
            transition(record, ActivationState.REVISION_ACCEPTED, ActivationState.EXTRACTING, 0);
            record = activations.read(request.tenantId(), request.sourceId(), request.generation()).orElseThrow();
        }
        if (record.state() == ActivationState.EXTRACTING) {
            transition(record, ActivationState.EXTRACTING, ActivationState.EXTRACTION_COMPLETE, 0);
            record = activations.read(request.tenantId(), request.sourceId(), request.generation()).orElseThrow();
        }
        if (record.state() == ActivationState.EXTRACTION_COMPLETE) {
            transition(record, ActivationState.EXTRACTION_COMPLETE, ActivationState.GRAPH_COMMITTING, 0);
            record = activations.read(request.tenantId(), request.sourceId(), request.generation()).orElseThrow();
        }
        if (record.state() == ActivationState.GRAPH_COMMITTING) {
            transition(record, ActivationState.GRAPH_COMMITTING, ActivationState.GRAPH_COMMITTED,
                    request.generation());
        }
    }

    private static void markSearchProjected(String tenant, String source, String resource,
                                            SourceVersionRow version) {
        SourceOutboxRow event = cache.listExtractionOutbox(tenant, version.connectorId(), source, resource)
                .stream().filter(row -> version.versionId().equals(row.sourceVersionId()))
                .findFirst().orElseThrow();
        cache.markSourceSearchProjected(event, Instant.now());
    }

    private static UUID firstActivationEventId(String tenant, String source, String resource) {
        UUID id = firstActivationEventIdOrNull(tenant, source, resource);
        assertThat(id).isNotNull();
        return id;
    }

    private static UUID firstActivationEventIdOrNull(String tenant, String source, String resource) {
        var row = session.execute("SELECT activation_event_id FROM source_versions "
                + "WHERE tenant_id=? AND connector_id=? AND source_id=? AND resource_id=?",
                tenant, "file", source, resource).one();
        return row == null ? null : row.getUuid("activation_event_id");
    }

    private static void advanceToSearchCommitted(ActivationRecord record) {
        ActivationRecord current = activations.read(record.tenantId(), record.sourceId(),
                record.generation()).orElse(record);
        if (current.state() == ActivationState.REVISION_ACCEPTED) {
            transition(current, ActivationState.REVISION_ACCEPTED, ActivationState.EXTRACTING, 0);
            current = read(record);
        }
        if (current.state() == ActivationState.EXTRACTING) {
            transition(current, ActivationState.EXTRACTING, ActivationState.EXTRACTION_COMPLETE, 0);
            current = read(record);
        }
        if (current.state() == ActivationState.EXTRACTION_COMPLETE) {
            transition(current, ActivationState.EXTRACTION_COMPLETE, ActivationState.GRAPH_COMMITTING, 0);
            current = read(record);
        }
        if (current.state() == ActivationState.GRAPH_COMMITTING) {
            transition(current, ActivationState.GRAPH_COMMITTING, ActivationState.GRAPH_COMMITTED,
                    current.generation());
            current = read(record);
        }
        if (current.state() == ActivationState.GRAPH_COMMITTED) {
            transition(current, ActivationState.GRAPH_COMMITTED, ActivationState.SEARCH_COMMITTING,
                    current.generation());
            current = read(record);
        }
        if (current.state() == ActivationState.SEARCH_COMMITTING) {
            transition(current, ActivationState.SEARCH_COMMITTING, ActivationState.SEARCH_COMMITTED,
                    current.generation());
        }
    }

    private static ActivationRecord read(ActivationRecord record) {
        return activations.read(record.tenantId(), record.sourceId(), record.generation())
                .orElseThrow();
    }

    private static void transition(ActivationRecord record, ActivationState expected,
                                   ActivationState next, long appliedGeneration) {
        boolean search = next == ActivationState.SEARCH_COMMITTED;
        assertThat(activations.transition(record.tenantId(), record.sourceId(), record.generation(),
                expected, next, new SourceActivationStore.ProjectionMarkers(
                        appliedGeneration, appliedGeneration > 0 ? "COMMITTED" : null,
                        search ? record.generation() : 0L, search ? "COMMITTED" : null),
                null, true, Instant.now())).isTrue();
    }

    private static long rowCount(String table, String tenant, String source) {
        var row = session.execute("SELECT COUNT(*) AS c FROM " + table
                + " WHERE tenant_id=? AND source_id=?", tenant, source).one();
        return row == null ? 0 : row.getLong("c");
    }
}
