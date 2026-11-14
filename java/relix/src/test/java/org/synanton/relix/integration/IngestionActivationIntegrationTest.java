package org.synanton.relix.integration;

import com.datastax.oss.driver.api.core.CqlSession;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.synanton.common.resolution.ActiveResolution;
import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.ExactTypeCompatibility;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.resolution.RelationValidationOutcome;
import org.synanton.common.resolution.RelationValidationResult;
import org.synanton.common.resolution.ResolutionCandidate;
import org.synanton.common.resolution.ResolutionCandidateProvider;
import org.synanton.common.resolution.ResolutionGraphLookup;
import org.synanton.common.resolution.SourceRevisionIdentity;
import org.synanton.common.resolution.PromotionResult;
import org.synanton.common.resolution.StaleGenerationException;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.ingestioncache.activation.ActivationRecord;
import org.synanton.ingestioncache.activation.ActivationState;
import org.synanton.ingestioncache.activation.CassandraSourceActivationStore;
import org.synanton.ingestioncache.activation.SourceActivationStore;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.claim.PromotionOwnershipStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.ActivationRequestRow;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ExtractionRequestRow;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.relix.adapter.out.graph.neo4j.DriverCypherExecutor;
import org.synanton.relix.adapter.out.graph.neo4j.Neo4jGraphConnector;
import org.synanton.relix.adapter.out.metadata.CassandraSourceGenerationAuthority;
import org.synanton.relix.adapter.out.metadata.Neo4jSourceFenceStore;
import org.synanton.relix.config.ActivationRecoveryProperties;
import org.synanton.relix.config.PromotionCompletionProperties;
import org.synanton.relix.config.PromotionConsumerProperties;
import org.synanton.relix.consumer.ActivationDriftMonitor;
import org.synanton.relix.consumer.ActivationRecoverySweep;
import org.synanton.relix.consumer.CanonicalGraphCompletion;
import org.synanton.relix.consumer.PromotionCompletionSweep;
import org.synanton.relix.consumer.PromotionConsumer;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.ExtractedRelation;
import org.synanton.relix.graph.RelixCanonicalRelationPromoter;
import org.synanton.relix.identity.ActivateRevision;
import org.synanton.relix.identity.SourceFenceKey;
import org.synanton.relix.identity.SourceFenceReconciler;
import org.synanton.relix.identity.SourceFenceStatus;
import org.synanton.relix.identity.SourceFenceStore;
import org.synanton.synflux.config.ExtractionConsumerProperties;
import org.synanton.synflux.config.ExtractionGraphProperties;
import org.synanton.synflux.events.IngestionEventConfig;
import org.synanton.synflux.extraction.ExtractionConsumer;
import org.synanton.synflux.extraction.graph.GraphMention;
import org.synanton.synflux.extraction.graph.GraphRelation;
import org.synanton.synflux.extraction.graph.GraphWriteClient;
import org.synanton.synflux.extraction.graph.GraphWriter;
import org.synanton.synflux.extraction.graph.RelationValidation;
import org.synanton.synflux.extraction.graph.RelationValidator;
import org.synanton.synflux.lifecycle.FixtureIngestionService;
import org.synanton.synflux.lifecycle.VerifiedIngestionContext;
import org.synanton.synflux.resolution.CanonicalRelationValidator;
import org.synanton.synflux.resolution.RelationPromotionProducer;
import org.synanton.synquest.config.ResolutionConsumerProperties;
import org.synanton.synquest.resolution.DefaultResolutionPolicy;
import org.synanton.synquest.resolution.ResolutionConsumer;
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
    private static PromotionOwnershipStore ownership;
    private static Neo4jSourceFenceStore fences;
    private static DriverCypherExecutor executor;
    private static FixtureIngestionService ingestion;

    @BeforeAll
    static void startStores() {
        cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1.12"));
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
        ownership = new PromotionOwnershipStore(session);
        ingestion = new FixtureIngestionService(cache, Clock.systemUTC(),
                new IngestionEventConfig().ingestionEventTypes());

        neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26.32")).withoutAuthentication();
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
    void mentionCommitDoesNotEmitActivationRequestedAndCanonicalCompletionIsIdempotent() {
        String tenant = "tenant-654-graph-lwt";
        String source = "source-654-graph-lwt";
        String resource = "graph.md";
        SourceVersionRow version = ingest(tenant, source, resource, ref(resource, "graph"), bytes("graph"));
        ExtractionRequestRow request = request(tenant, version);
        commitMentions(request, version);

        assertThat(cache.readGraphStatus(tenant, "file", source, resource, version.versionId()))
                .contains("MENTIONS_COMMITTED");
        assertThat(activations.read(tenant, source, version.generation())).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.MENTIONS_COMMITTED);
        assertThat(firstActivationEventIdOrNull(tenant, source, resource)).isNull();
        assertThat(cache.listPendingActivationRequests(tenant, 10, Instant.now())).isEmpty();

        sweep(tenant).sweep(new ActivationRequestRow(tenant, "file", source, resource,
                UUID.randomUUID(), version.versionId(), version.generation(), version.contentHash(),
                null, "{}", version.observedAt(), Instant.now(), "MENTIONS_COMMITTED",
                0, null, null, false));
        assertThat(activations.read(tenant, source, version.generation())).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.MENTIONS_COMMITTED);
        assertThat(firstActivationEventIdOrNull(tenant, source, resource)).isNull();

        acquireAndCloseOwnership(tenant, version);
        new PromotionCompletionSweep(cache, completion(),
                new PromotionCompletionProperties(true, tenant, 100, 30_000L),
                new SimpleMeterRegistry()).poll();

        assertThat(cache.readGraphStatus(tenant, "file", source, resource, version.versionId()))
                .contains("GRAPH_COMMITTED");
        UUID originalEvent = firstActivationEventId(tenant, source, resource);
        assertThat(cache.listPendingActivationRequests(tenant, 10, Instant.now())).hasSize(1);

        boolean repeated = cache.completeCanonicalGraphCommit(tenant, "file", source, resource,
                version.versionId(), UUID.randomUUID(), "ActivationRequested", "{}", Instant.now());
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
        commitMentions(request(tenant, version), version);
        completeCanonicalThroughPromotion(tenant, version);
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
        commitMentions(request(tenant, version), version);
        completeCanonical(version);
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

        commitMentions(request(tenant, version), version);
        assertThat(activations.read(tenant, source, version.generation())).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.MENTIONS_COMMITTED);
        assertThat(cache.listPendingActivationRequests(tenant, 10, Instant.now())).isEmpty();
        completeCanonicalThroughPromotion(tenant, version);
        assertThat(activations.read(tenant, source, version.generation())).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.GRAPH_COMMITTED);
        assertThat(cache.listPendingActivationRequests(tenant, 10, Instant.now())).hasSize(1);
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

    @Test
    void fullChainFromIngestToActiveProducesCorrectCassandraAndNeo4jState() {
        String tenant = "tenant-654-full-chain";
        String tenantB = "tenant-654-full-chain-isolation";
        String source = "source-654-full-chain";
        String resource = "full-chain.md";
        String text = "Acme requires Protocol-A";
        String subjectEntity = "entity-654-full-chain-subject";
        String objectEntity = "entity-654-full-chain-object";
        SourceVersionRow version = ingest(tenant, source, resource, ref(resource, text), bytes(text));

        // Acceptance used the existing FixtureIngestionService entry available to this class.
        cache.insertChunks(List.of(new ChunkRow(tenant, version.contentRefId(), 0, text, "chunk-full-chain")));
        markSearchProjected(tenant, source, resource, version);

        Neo4jGraphConnector graph = new Neo4jGraphConnector(null, executor);
        GraphWriter graphWriter = new GraphWriter(cache,
                (tenantId, subjectType, predicate, objectType, ontologyVersion) ->
                        new RelationValidation(true, List.of()),
                new Neo4jGraphWriteClient(graph),
                new ExtractionGraphProperties(true, null, null, "active"),
                Clock.systemUTC(), activations);
        ExtractionConsumer extraction = new ExtractionConsumer(cache,
                new TwoMentionExtractionAdapter(),
                new ExtractionConsumerProperties(true, tenant, 10, 5, 1, 1000L),
                graphWriter, Clock.systemUTC());
        await("extraction to MENTIONS_COMMITTED", extraction::poll,
                () -> "MENTIONS_COMMITTED".equals(cache.readGraphStatus(
                        tenant, "file", source, resource, version.versionId()).orElse(null)),
                () -> chainDiagnostics(tenant, source, resource, version));

        executor.write("MERGE (e:Entity {tenant: $tenant, id: $id}) "
                        + "SET e.label = $label, e.type = $type",
                Map.of("tenant", tenant, "id", subjectEntity, "label", "Acme",
                        "type", "Organization"));
        executor.write("MERGE (e:Entity {tenant: $tenant, id: $id}) "
                        + "SET e.label = $label, e.type = $type",
                Map.of("tenant", tenant, "id", objectEntity, "label", "Protocol-A",
                        "type", "Protocol"));

        ResolutionCandidateProvider candidates = context -> switch (context.surfaceForm()) {
            case "Acme" -> List.of(new ResolutionCandidate(subjectEntity, "Acme",
                    "Organization", 0.9, "label", "RRF", "gen-654-full-chain"));
            case "Protocol-A" -> List.of(new ResolutionCandidate(objectEntity, "Protocol-A",
                    "Protocol", 0.9, "label", "RRF", "gen-654-full-chain"));
            default -> List.of();
        };
        ResolutionConsumer resolution = new ResolutionConsumer(new ClaimService(cache), candidates,
                new DefaultResolutionPolicy("v1", 0.02, 0.001, new ExactTypeCompatibility()),
                graph::upsertResolution,
                new ResolutionConsumerProperties(true, tenant, 10, 5, 1, 60, 1000L, "active"),
                new SimpleMeterRegistry());
        await("resolution completion", resolution::poll,
                () -> {
                    var rows = cache.listResolutionWorkRows(tenant, version.versionId());
                    return !rows.isEmpty() && rows.stream()
                            .allMatch(row -> "COMPLETED".equals(row.claimStatus()));
                },
                () -> "resolution_work=" + cache.listResolutionWorkRows(tenant, version.versionId()));

        RelationPromotionProducer producer = new RelationPromotionProducer(
                new ResolutionGraphLookup() {
                    @Override
                    public List<ActiveResolution> getActiveResolutions(
                            String tenantId, List<String> mentionIds) {
                        return graph.getActiveResolutions(tenantId, mentionIds);
                    }
                },
                graph,
                (tenantId, ontologyVersion, extractedRelationId) -> new RelationValidationResult(
                        RelationValidationOutcome.VALID, subjectEntity, "requires", objectEntity,
                        "Organization", "Protocol", ontologyVersion, List.of(), "VALID"),
                cache, ownership, Clock.systemUTC(), true, tenant, "active");
        fences.advance(new SourceFenceKey(tenant, source), version.generation());
        await("promotion row production and ownership closure", producer::poll,
                () -> ownership.isClosed(tenant, version.versionId())
                        && !cache.listRelationPromotionWorkRows(tenant, version.versionId()).isEmpty(),
                () -> "ownership=" + ownership.read(tenant, version.versionId())
                        + ", promotion_rows=" + cache.listRelationPromotionWorkRows(
                                tenant, version.versionId()));

        CanonicalGraphCompletion completion = new CanonicalGraphCompletion(
                cache, activations, ownership, Clock.systemUTC());
        RelixCanonicalRelationPromoter promoter = new RelixCanonicalRelationPromoter(cache, graph,
                tenantId -> GraphIdentity.canonical(tenantId, "v1"),
                new CassandraSourceGenerationAuthority(cache));
        PromotionConsumer promotion = new PromotionConsumer(new ClaimService(cache), promoter,
                new PromotionConsumerProperties(true, tenant, 10, 5, 1, 120, 1000L),
                new SimpleMeterRegistry(), completion, ownership);
        await("promotion to GRAPH_COMMITTED", promotion::poll,
                () -> "GRAPH_COMMITTED".equals(cache.readGraphStatus(
                        tenant, "file", source, resource, version.versionId()).orElse(null)),
                () -> chainDiagnostics(tenant, source, resource, version));

        ActivationRecoverySweep sweep = sweep(tenant);
        await("activation recovery to CURRENT", sweep::poll,
                () -> activations.readLifecycle(tenant, source)
                        .map(lifecycle -> lifecycle.currentGeneration() == version.generation())
                        .orElse(false),
                () -> "lifecycle=" + activations.readLifecycle(tenant, source)
                        + ", activation=" + activations.read(tenant, source, version.generation()));

        var sourceRow = session.execute("SELECT status FROM ingestion_cache.sources "
                + "WHERE tenant_id=? AND source_id=?", tenant, source).one();
        assertThat(sourceRow).isNotNull();
        assertThat(sourceRow.getString("status")).isEqualTo("ACTIVE");

        var versionRow = session.execute("SELECT graph_status, generation, accepted_generation "
                + "FROM ingestion_cache.source_versions WHERE tenant_id=? AND connector_id=? "
                + "AND source_id=? AND resource_id=? AND version_id=?",
                tenant, "file", source, resource, version.versionId()).one();
        assertThat(versionRow).isNotNull();
        assertThat(versionRow.getString("graph_status")).isEqualTo("GRAPH_COMMITTED");
        assertThat((long) versionRow.getInt("generation")).isEqualTo(version.generation());
        assertThat((long) versionRow.getInt("accepted_generation")).isEqualTo(version.generation());

        var currentRow = session.execute("SELECT accepted_generation, current_generation, "
                + "active_revision_id FROM ingestion_cache.source_current "
                + "WHERE tenant_id=? AND source_id=?", tenant, source).one();
        assertThat(currentRow).isNotNull();
        assertThat(currentRow.getLong("accepted_generation")).isEqualTo(version.generation());
        assertThat(currentRow.getLong("current_generation")).isEqualTo(version.generation());
        assertThat(currentRow.getUuid("active_revision_id")).isEqualTo(version.versionId());

        var activationRow = session.execute("SELECT state, revision_id, "
                + "graph_applied_generation, search_applied_generation "
                + "FROM ingestion_cache.source_activation WHERE tenant_id=? AND source_id=? "
                + "AND generation=?", tenant, source, version.generation()).one();
        assertThat(activationRow).isNotNull();
        assertThat(activationRow.getString("state")).isEqualTo("CURRENT");
        assertThat(activationRow.getUuid("revision_id")).isEqualTo(version.versionId());
        assertThat(activationRow.getLong("graph_applied_generation"))
                .isEqualTo(version.generation());
        assertThat(activationRow.getLong("search_applied_generation"))
                .isEqualTo(version.generation());

        var ownershipRow = session.execute("SELECT owner_closed, owner_generation "
                + "FROM ingestion_cache.relation_promotion_results "
                + "WHERE tenant_id=? AND source_version_id=? AND relation_id=?",
                tenant, version.versionId(),
                PromotionOwnershipStore.OWNERSHIP_RELATION_ID).one();
        assertThat(ownershipRow).isNotNull();
        assertThat(ownershipRow.getBoolean("owner_closed")).isTrue();
        assertThat(ownershipRow.getLong("owner_generation")).isEqualTo(1L);

        assertThat(cache.listRelationPromotionWorkRows(tenant, version.versionId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.claimStatus()).isEqualTo("COMPLETED");
                    assertThat(row.result().promotionStatus()).isEqualTo("COMPLETED");
                });

        Map<String, Object> relParams = Map.of(
                "tenant", tenant,
                "subjectEntityId", subjectEntity,
                "objectEntityId", objectEntity,
                "predicate", "requires",
                "sourceVersionId", version.versionId().toString(),
                "sourceId", source,
                "generation", version.generation());
        assertThat(((Number) executor.read("""
                MATCH (s:Entity {tenant: $tenant, id: $subjectEntityId})
                      -[r:REL {tenant_id: $tenant, predicate: $predicate}]->
                      (o:Entity {tenant: $tenant, id: $objectEntityId})
                RETURN count(r) AS c
                """, relParams).getFirst().get("c")).longValue()).isEqualTo(1L);
        List<Map<String, Object>> relRows = executor.read("""
                MATCH (s:Entity {tenant: $tenant, id: $subjectEntityId})
                      -[r:REL {tenant_id: $tenant, predicate: $predicate}]->
                      (o:Entity {tenant: $tenant, id: $objectEntityId})
                MATCH (e:RelationEvidence {tenant_id: $tenant,
                      canonical_relation_id: r.canonical_relation_id})
                WHERE e.source_version_id = $sourceVersionId
                  AND e.source_id = $sourceId
                  AND e.generation = $generation
                RETURN r.tenant_id AS tenant_id, e.source_version_id AS source_version_id,
                       e.source_id AS source_id, e.generation AS generation,
                       r.predicate AS predicate,
                       r.canonical_relation_id AS canonical_relation_id
                """, relParams);
        assertThat(relRows).hasSize(1);
        Map<String, Object> rel = relRows.getFirst();
        assertThat(rel.get("tenant_id")).isEqualTo(tenant);
        assertThat(rel.get("source_version_id")).isEqualTo(version.versionId().toString());
        assertThat(rel.get("source_id")).isEqualTo(source);
        assertThat(((Number) rel.get("generation")).longValue()).isEqualTo(version.generation());
        assertThat(rel.get("predicate")).isEqualTo("requires");
        String canonicalRelationId = String.valueOf(rel.get("canonical_relation_id"));

        List<Map<String, Object>> evidenceRows = executor.read("""
                MATCH (xr:ExtractedRelation {tenant_id: $tenant})
                      -[:SUPPORTS]->(e:RelationEvidence {tenant_id: $tenant})
                WHERE e.source_version_id = $sourceVersionId
                  AND e.source_id = $sourceId
                  AND e.generation = $generation
                  AND e.canonical_relation_id = $canonicalRelationId
                RETURN e.evidence_chunk_id AS chunk_id
                """, Map.of("tenant", tenant,
                "sourceVersionId", version.versionId().toString(),
                "sourceId", source,
                "generation", version.generation(),
                "canonicalRelationId", canonicalRelationId));
        assertThat(evidenceRows).hasSize(1);
        assertThat(String.valueOf(evidenceRows.getFirst().get("chunk_id")))
                .isNotBlank()
                .isEqualTo(version.contentRefId() + ":0");

        executor.write("MERGE (e:Entity {tenant: $tenant, id: $id}) "
                        + "SET e.label = $label, e.type = $type",
                Map.of("tenant", tenantB, "id", subjectEntity, "label", "Acme",
                        "type", "Organization"));
        executor.write("MERGE (e:Entity {tenant: $tenant, id: $id}) "
                        + "SET e.label = $label, e.type = $type",
                Map.of("tenant", tenantB, "id", objectEntity, "label", "Protocol-A",
                        "type", "Protocol"));
        Map<String, Object> isolatedParams = new java.util.HashMap<>(relParams);
        isolatedParams.put("tenant", tenantB);
        assertThat(((Number) executor.read("""
                MATCH (s:Entity {tenant: $tenant, id: $subjectEntityId})
                      -[r:REL {tenant_id: $tenant, predicate: $predicate}]->
                      (o:Entity {tenant: $tenant, id: $objectEntityId})
                RETURN count(r) AS c
                """, isolatedParams).getFirst().get("c")).longValue()).isZero();
        assertThat(executor.read("""
                MATCH (s:Entity {tenant: $tenant, id: $subjectEntityId})
                      -[r:REL {tenant_id: $tenant, predicate: $predicate}]->
                      (o:Entity {tenant: $tenant, id: $objectEntityId})
                MATCH (e:RelationEvidence {tenant_id: $tenant,
                      canonical_relation_id: r.canonical_relation_id})
                WHERE e.source_version_id = $sourceVersionId
                  AND e.source_id = $sourceId
                  AND e.generation = $generation
                RETURN r.canonical_relation_id AS canonical_relation_id
                """, isolatedParams)).isEmpty();
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

    private static CanonicalGraphCompletion completion() {
        return new CanonicalGraphCompletion(cache, activations, ownership, Clock.systemUTC());
    }

    private static PromotionOwnershipStore.OwnershipToken acquireOwnership(
            String tenant, SourceVersionRow version) {
        return ownership.acquire(tenant, version.versionId(), "test-producer-" + version.versionId())
                .orElseThrow();
    }

    private static void acquireAndCloseOwnership(String tenant, SourceVersionRow version) {
        ownership.closeSet(tenant, version.versionId(), acquireOwnership(tenant, version));
    }

    private static void commitMentions(ExtractionRequestRow request, SourceVersionRow version) {
        assertThat(cache.claimGraphCommit(request)).isTrue();
        assertThat(cache.markGraphCommitted(request, "MENTIONS_COMMITTED", Map.of(), 0,
                Instant.now(), null, null, null)).isTrue();
        advanceActivationToMentionsCommitted(request);
    }

    private static void completeCanonical(SourceVersionRow version) {
        acquireAndCloseOwnership(version.tenantId(), version);
        assertThat(completion().completeIfPromotionTerminal(version)).isTrue();
    }

    private static void completeCanonicalThroughPromotion(String tenant, SourceVersionRow version) {
        PromotionOwnershipStore.OwnershipToken token = acquireOwnership(tenant, version);
        String relationId = "relation-" + version.versionId();
        String evidence = """
                {"view":{"relationId":"%s","tenantId":"%s","sourceVersionId":"%s",
                "subjectMentionId":"mention-1","objectMentionId":"mention-2","predicate":"requires",
                "evidenceChunkId":"%s:0","evidenceChunkOffsetStart":0,"evidenceChunkOffsetEnd":1}}
                """.formatted(relationId, tenant, version.versionId(), version.contentRefId());
        ownership.insertPromotionResult(new RelationPromotionResultRow(
                tenant, version.versionId(), relationId, "mention-1", "mention-2", "requires",
                "VALID", "entity-1", "entity-2", "VALID", evidence, "relation-validation-v1",
                "active", Instant.now(), null, version.sourceId(), version.versionId(),
                version.generation(), "run-654", "CURRENT"), token);
        ownership.closeSet(tenant, version.versionId(), token);
        PromotionConsumer consumer = new PromotionConsumer(new ClaimService(cache),
                relation -> new PromotionResult(1, 1, 1),
                new PromotionConsumerProperties(true, tenant, 10, 5, 30, 120, 10_000L),
                new SimpleMeterRegistry(), completion(), ownership);
        consumer.poll();
        assertThat(cache.readGraphStatus(tenant, version.connectorId(), version.sourceId(),
                version.resourceId(), version.versionId())).contains("GRAPH_COMMITTED");
        assertThat(activations.read(tenant, version.sourceId(), version.generation())).get()
                .extracting(ActivationRecord::state).isEqualTo(ActivationState.GRAPH_COMMITTED);
    }

    private static void advanceActivationToMentionsCommitted(ExtractionRequestRow request) {
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
            transition(record, ActivationState.GRAPH_COMMITTING, ActivationState.MENTIONS_COMMITTED,
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
            transition(current, ActivationState.GRAPH_COMMITTING, ActivationState.MENTIONS_COMMITTED,
                    current.generation());
            current = read(record);
        }
        if (current.state() == ActivationState.MENTIONS_COMMITTED) {
            transition(current, ActivationState.MENTIONS_COMMITTED, ActivationState.GRAPH_COMMITTED,
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

    private static String chainDiagnostics(String tenant, String source, String resource,
                                           SourceVersionRow version) {
        return "extraction_status=" + cache.readExtractionStatus(
                tenant, version.connectorId(), source, resource, version.versionId())
                + ", graph_status=" + cache.readGraphStatus(
                        tenant, version.connectorId(), source, resource, version.versionId())
                + ", activation=" + activations.read(tenant, source, version.generation())
                + ", pending_activation=" + cache.listPendingActivationRequests(
                        tenant, 10, Instant.now())
                + ", resolution_work=" + cache.listResolutionWorkRows(tenant, version.versionId())
                + ", promotion_rows=" + cache.listRelationPromotionWorkRows(
                        tenant, version.versionId());
    }

    private static void await(String description, Runnable poll, BooleanSupplier condition,
                              Supplier<String> diagnostics) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            poll.run();
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, e);
            }
        }
        throw new AssertionError("Timed out waiting for " + description + "; state: "
                + diagnostics.get());
    }

    private static final class TwoMentionExtractionAdapter implements ExtractionEngineAdapter {
        @Override
        public ExtractionResult extract(TextSegment segment, ExtractionContext context) {
            return new ExtractionResult(
                    List.of(
                            new PreEntity("subject", "Acme", "Organization", 0, 4, "Acme"),
                            new PreEntity("object", "Protocol-A", "Protocol", 13, 23,
                                    "Protocol-A")),
                    List.of(new RelationCandidate("subject", "requires", "object",
                            "chunk-full-chain", 0, 23)));
        }
    }

    private static final class Neo4jGraphWriteClient implements GraphWriteClient {
        private final Neo4jGraphConnector graph;

        private Neo4jGraphWriteClient(Neo4jGraphConnector graph) {
            this.graph = graph;
        }

        @Override
        public int upsertMentions(List<GraphMention> mentions) {
            return graph.upsertMentions(mentions.stream()
                    .map(mention -> new ExtractedMention(mention.mentionId(),
                            mention.tenantId(), mention.sourceVersionId(),
                            mention.contentRefId(), mention.chunkId(),
                            mention.chunkOffsetStart(), mention.chunkOffsetEnd(),
                            mention.documentOffsetStart(), mention.documentOffsetEnd(),
                            mention.surfaceForm(), mention.proposedType(),
                            mention.extractedAt()))
                    .toList());
        }

        @Override
        public int upsertRelations(List<GraphRelation> relations) {
            return graph.upsertRelations(relations.stream()
                    .map(relation -> new ExtractedRelation(relation.relationId(),
                            relation.tenantId(), relation.sourceVersionId(),
                            relation.predicate(), relation.subjectMentionId(),
                            relation.objectMentionId(), relation.evidenceChunkId(),
                            relation.evidenceChunkOffsetStart(),
                            relation.evidenceChunkOffsetEnd(),
                            relation.evidenceDocumentOffsetStart(),
                            relation.evidenceDocumentOffsetEnd(),
                            relation.validatedAt(), relation.sourceId(),
                            relation.revisionId(), relation.generation(),
                            relation.extractionRunId()))
                    .toList());
        }
    }
}
