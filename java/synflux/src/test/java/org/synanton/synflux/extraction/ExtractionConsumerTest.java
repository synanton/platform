package org.synanton.synflux.extraction;

import org.synanton.synflux.SynfluxCassandraTestSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.ExtractionIntegrity;
import org.synanton.extraction.engine.ExtractionIntegrity;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.LlmProvenance;
import org.synanton.extraction.engine.LlmUsage;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.QuarantinedLine;
import org.synanton.extraction.engine.llm.LlmIncompleteOutputException;
import org.synanton.extraction.engine.provenance.ExtractionCacheKey;
import org.synanton.synflux.extraction.ontology.OntologyRelease;
import org.synanton.synflux.extraction.ontology.OntologyReleaseResolver;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.ingestioncache.cache.ExtractionCacheStore;
import org.synanton.ingestioncache.claim.PromotionOwnershipStore;
import org.synanton.ingestioncache.quarantine.QuarantineGeneration;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ExtractionRequestRow;
import org.synanton.ingestioncache.domain.OntologyReleaseRef;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.ingestioncache.quarantine.QuarantineStore;
import org.synanton.ingestioncache.domain.ResolutionResultRow;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.synflux.config.ExtractionConsumerProperties;
import org.synanton.synflux.extraction.cache.ExtractionCacheService;
import org.synanton.synflux.extraction.graph.GraphCommitResult;
import org.synanton.synflux.extraction.graph.GraphWriter;
import org.synanton.synflux.extraction.provider.ExtractionAdapterResolver;
import org.synanton.synflux.extraction.provider.ExtractionProvider;
import org.synanton.synflux.extraction.provider.ProviderConfigInvalidException;
import org.synanton.synflux.extraction.provider.ResolvedExtractionAdapter;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

class ExtractionConsumerTest {

    private static final String CONNECTOR = "upload";
    private static final String SOURCE = "extraction-source";
    private static final String RESOURCE = "docs/halcyon.txt";

    private static CqlSession session;
    private static IngestionCacheClient cache;

    @BeforeAll
    static void startCassandra() {
        session = SynfluxCassandraTestSupport.session();
        SynfluxCassandraTestSupport.reset();
        cache = new IngestionCacheClient(session);
    }


    @Test
    void readyRequestRunsTheAdapterAndReachesKnowledgeReady() {
        Fixture fixture = seed("tenant-extraction-ok", "Halcyon-7 requires a helium leak check.");
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenReturn(new ExtractionResult(
                List.of(new PreEntity("m1", "Halcyon-7", "MaintenanceSystem", 0, 9, "Halcyon-7"),
                        new PreEntity("m2", "helium leak check", "Procedure", 23, 40, "Halcyon-7")),
                List.of(new RelationCandidate("m1", "requires", "m2", "c1", 0, 41))));

        consumer("tenant-extraction-ok", adapter).poll();

        assertThat(cache.readExtractionStatus("tenant-extraction-ok", CONNECTOR, SOURCE, RESOURCE,
                fixture.versionId())).contains("KNOWLEDGE_READY");
        assertThat(cache.listExtractionOutbox("tenant-extraction-ok", CONNECTOR, SOURCE, RESOURCE))
                .hasSize(1)
                .allSatisfy(event -> assertThat(event.published()).isTrue());
        assertThat(cache.listExtractionResults("tenant-extraction-ok", fixture.contentRefId()))
                .hasSize(1)
                .allSatisfy(result -> {
                    assertThat(result.entitiesJson()).contains("Halcyon-7");
                    assertThat(result.relationsJson()).contains("requires");
                });
        verify(adapter, times(1)).extract(any(), any());
    }

    @Test
    void completionLogCarriesBoundedTenantClassAndPerStageDurations() throws Exception {
        String tenant = "eval-log-schema";
        seed(tenant, "Halcyon-7 requires a helium leak check.");
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenReturn(new ExtractionResult(
                List.of(new PreEntity("m1", "Halcyon-7", "MaintenanceSystem", 0, 9, "Halcyon-7")),
                List.of()));

        Logger logger = (Logger) LoggerFactory.getLogger(ExtractionConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            consumer(tenant, adapter).poll();
        } finally {
            logger.detachAppender(appender);
        }

        String message = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains("\"event\":\"extraction_completed\""))
                .findFirst()
                .orElseThrow();
        JsonNode event = new ObjectMapper().readTree(message.substring(message.indexOf('{')));
        assertThat(event.path("tenant_class").asText()).isEqualTo("eval");
        assertThat(event.path("tenant_id").asText()).isEqualTo(tenant);
        assertThat(event.path("rejection_reason_codes").isArray()).isTrue();
        JsonNode durations = event.path("durations_ms");
        for (String stage : List.of("extraction", "indexing", "promotion", "activation")) {
            assertThat(durations.path(stage).asLong()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void registryPollProcessesTwoTenantsConcurrentlyWithoutCrossContamination() throws Exception {
        String tenantA = "tenant-concurrent-a";
        String tenantB = "tenant-concurrent-b";
        Fixture a = seed(tenantA, "Halcyon-7 requires a helium leak check.");
        Fixture b = seed(tenantB, "A second tenant needs an independent extraction.");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenAnswer(invocation -> {
            ExtractionContext context = invocation.getArgument(1);
            assertThat(context.tenantId()).isIn(tenantA, tenantB);
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent tenant did not enter");
            }
            return new ExtractionResult(List.of(), List.of());
        });
        ExtractionConsumer consumer = new ExtractionConsumer(cache, adapter,
                new ExtractionConsumerProperties(true, "", 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC());

        Thread poller = new Thread(consumer::poll, "registry-poll");
        poller.start();
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS))
                    .as("both tenants must be processed concurrently")
                    .isTrue();
        } finally {
            release.countDown();
        }
        poller.join(10_000);
        assertThat(poller.isAlive()).isFalse();

        assertThat(calls).hasValue(2);
        assertThat(cache.readExtractionStatus(tenantA, CONNECTOR, SOURCE, RESOURCE,
                a.versionId())).contains("KNOWLEDGE_READY");
        assertThat(cache.readExtractionStatus(tenantB, CONNECTOR, SOURCE, RESOURCE,
                b.versionId())).contains("KNOWLEDGE_READY");
    }

    @Test
    void incompleteLlmOutputIsTerminalAndDeadLettered() {
        String tenant = "tenant-incomplete-llm";
        seed(tenant, "Halcyon-7 requires a helium leak check.");
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenThrow(new LlmIncompleteOutputException(
                new LlmUsage("length", 1328, 2048, 3376), 6736, "a".repeat(64)));

        consumer(tenant, adapter).poll();

        assertThat(cache.readExtractionStatus(tenant, CONNECTOR, SOURCE, RESOURCE,
                latestVersion(tenant))).contains("EXTRACTION_FAILED");
        assertThat(cache.listExtractionOutbox(tenant, CONNECTOR, SOURCE, RESOURCE))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.deadLettered()).isTrue();
                    assertThat(event.nextAttemptAt()).isNull();
                    assertThat(event.lastError()).contains("INCOMPLETE_LLM_OUTPUT")
                            .contains("finish_reason=length");
                });
    }

    @Test
    void corruptedJsonlIsFailedAndNotPersistedAsSuccess() {
        String tenant = "tenant-corrupted-jsonl";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenReturn(new ExtractionResult(
                List.of(), List.of(), LlmUsage.NONE,
                List.of(new QuarantinedLine(2, "{bad}", "JSON_PARSE", "bad line", "STRUCTURAL")),
                ExtractionIntegrity.CORRUPTED));

        consumer(tenant, adapter).poll();

        assertThat(cache.readExtractionStatus(tenant, CONNECTOR, SOURCE, RESOURCE,
                fixture.versionId())).contains("EXTRACTION_FAILED");
        assertThat(cache.listExtractionResults(tenant, fixture.contentRefId())).isEmpty();
        assertThat(cache.listExtractionOutbox(tenant, CONNECTOR, SOURCE, RESOURCE))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.published()).isFalse();
                    assertThat(event.attemptCount()).isEqualTo(1);
                    assertThat(event.lastError()).contains("CORRUPTED_RESPONSE");
                });
    }

    @Test
    void extractionCreatesStagedGenerationAndPersistsQuarantineAndAudit() {
        String tenant = "tenant-generation-lifecycle";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        QuarantineStore quarantineStore = new QuarantineStore(session);
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenReturn(new ExtractionResult(
                List.of(), List.of(), LlmUsage.NONE,
                List.of(new QuarantinedLine(1, "{bad}", "UNKNOWN_ENTITY_TYPE", "Alien", "SEMANTIC")),
                ExtractionIntegrity.CLEAN,
                new LlmProvenance("extraction-v2", "a".repeat(64), "b".repeat(64), null, null)));

        new ExtractionConsumer(cache, adapter,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC(), quarantineStore).poll();

        assertThat(cache.readExtractionStatus(tenant, CONNECTOR, SOURCE, RESOURCE,
                fixture.versionId())).contains("KNOWLEDGE_READY");
        var generations = quarantineStore.generations(tenant, fixture.versionId());
        assertThat(generations).hasSize(1);
        UUID generationId = generations.getFirst().extractionGeneration();
        assertThat(generations.getFirst().generationStatus()).isEqualTo("STAGED");
        assertThat(generations.getFirst().promptDigest()).isEqualTo("a".repeat(64));
        assertThat(quarantineStore.records(tenant, fixture.versionId(), generationId)).hasSize(1);
        assertThat(quarantineStore.currentGeneration(tenant, fixture.versionId())).isEmpty();
        assertThat(quarantineStore.auditEvents(tenant, fixture.versionId(), generationId))
                .extracting(event -> event.eventType())
                .contains("GENERATION_CREATED");
    }

    @Test
    void persistedExtractionCarriesPromptProvenance() {
        String tenant = "tenant-provenance";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenReturn(new ExtractionResult(
                List.of(), List.of(), LlmUsage.NONE, List.of(), ExtractionIntegrity.CLEAN,
                new LlmProvenance("extraction-v2", "a".repeat(64), "b".repeat(64), null, null)));

        consumer(tenant, adapter).poll();

        assertThat(cache.listExtractionResults(tenant, fixture.contentRefId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.promptVersion()).isEqualTo("extraction-v2");
                    assertThat(row.promptCompiledDigest()).isEqualTo("a".repeat(64));
                    assertThat(row.promptRenderedDigest()).isEqualTo("b".repeat(64));
                    assertThat(row.schemaDigest()).isNull();
                });
    }

    @Test
    void failedExtractionLeavesNoStagedGeneration() {
        String tenant = "tenant-generation-failure";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        QuarantineStore quarantineStore = new QuarantineStore(session);
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenThrow(new IllegalStateException("model down"));

        new ExtractionConsumer(cache, adapter,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC(), quarantineStore).poll();

        assertThat(cache.readExtractionStatus(tenant, CONNECTOR, SOURCE, RESOURCE,
                fixture.versionId())).contains("EXTRACTION_FAILED");
        assertThat(quarantineStore.generations(tenant, fixture.versionId())).isEmpty();
        assertThat(quarantineStore.currentGeneration(tenant, fixture.versionId())).isEmpty();
    }

    @Test
    void adapterFailureLeavesTheRequestRetryable() {
        seed("tenant-extraction-fail", "Halcyon-7 requires a helium leak check.");
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenThrow(new IllegalStateException("vllm down"));

        consumer("tenant-extraction-fail", adapter).poll();

        var status = cache.readExtractionStatus("tenant-extraction-fail", CONNECTOR, SOURCE, RESOURCE,
                latestVersion("tenant-extraction-fail"));
        assertThat(status).contains("EXTRACTION_FAILED");
        assertThat(cache.listExtractionOutbox("tenant-extraction-fail", CONNECTOR, SOURCE, RESOURCE))
                .hasSize(1)
                .allSatisfy(event -> {
                    assertThat(event.published()).isFalse();
                    assertThat(event.attemptCount()).isEqualTo(1);
                    assertThat(event.nextAttemptAt()).isNotNull();
                });
    }

    @Test
    void providerInvalidMarksTheDocumentTerminal() {
        String tenant = "tenant-provider-invalid";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        ExtractionAdapterResolver resolver = new ExtractionAdapterResolver() {
            @Override
            public ResolvedExtractionAdapter forTenant(String tenantId) {
                throw new ProviderConfigInvalidException("bad provider");
            }

            @Override
            public ResolvedExtractionAdapter forProvider(String provider) {
                throw new ProviderConfigInvalidException("bad provider");
            }
        };
        ExtractionConsumer consumer = new ExtractionConsumer(cache, resolver,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC());

        consumer.poll();

        assertThat(cache.readExtractionStatus(tenant, CONNECTOR, SOURCE, RESOURCE,
                fixture.versionId())).contains("EXTRACTION_FAILED");
        assertThat(cache.listExtractionOutbox(tenant, CONNECTOR, SOURCE, RESOURCE))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.deadLettered()).isTrue();
                    assertThat(event.nextAttemptAt()).isNull();
                    assertThat(event.lastError()).contains("PROVIDER_CONFIG_INVALID");
                });
    }

    @Test
    void resolvedProviderIsPersistedOnTheSourceVersion() {
        String tenant = "tenant-provider-metadata";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenReturn(new ExtractionResult(
                List.of(new PreEntity("m1", "Halcyon-7", "MaintenanceSystem", 0, 9, "Halcyon-7")),
                List.of()));
        ResolvedExtractionAdapter resolved = new ResolvedExtractionAdapter(
                adapter, ExtractionProvider.LLM, "tenant-override");
        ExtractionAdapterResolver resolver = new ExtractionAdapterResolver() {
            @Override
            public ResolvedExtractionAdapter forTenant(String tenantId) {
                return resolved;
            }

            @Override
            public ResolvedExtractionAdapter forProvider(String provider) {
                return resolved;
            }
        };
        ExtractionConsumer consumer = new ExtractionConsumer(cache, resolver,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC());

        consumer.poll();

        String provider = session.execute(
                "SELECT extraction_provider FROM ingestion_cache.source_versions " +
                        "WHERE tenant_id=? AND connector_id=? AND source_id=? AND resource_id=? " +
                        "AND version_id=?",
                tenant, CONNECTOR, SOURCE, RESOURCE, fixture.versionId())
                .one().getString("extraction_provider");
        assertThat(provider).isEqualTo("llm");
    }

    @Test
    void tenantProviderOverrideRoundTripsAndCanBeCleared() {
        String tenant = "tenant-provider-config";
        Instant now = Instant.now();
        cache.setTenantProvider(tenant, "llm", now);
        assertThat(cache.readTenantProvider(tenant)).contains("llm");
        cache.clearTenantProvider(tenant);
        assertThat(cache.readTenantProvider(tenant)).isEmpty();
    }

    @Test
    void retryUsesPersistedProviderAfterConfigChange() {
        String tenant = "tenant-provider-retry";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any()))
                .thenThrow(new IllegalStateException("first attempt fails"))
                .thenReturn(new ExtractionResult(List.of(), List.of()));
        ResolvedExtractionAdapter llm = new ResolvedExtractionAdapter(
                adapter, ExtractionProvider.LLM, "tenant-override");
        ExtractionAdapterResolver resolver = mock(ExtractionAdapterResolver.class);
        when(resolver.forTenant(tenant)).thenReturn(llm);
        when(resolver.forProvider("llm")).thenReturn(
                new ResolvedExtractionAdapter(adapter, ExtractionProvider.LLM, "persisted"));

        Instant t0 = Instant.now();
        ExtractionConsumer first = new ExtractionConsumer(cache, resolver,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.fixed(t0, ZoneOffset.UTC));
        first.poll();
        assertThat(cache.readExtractionProvider(new org.synanton.ingestioncache.domain.ExtractionRequestRow(
                tenant, CONNECTOR, SOURCE, RESOURCE, UUID.randomUUID(), fixture.versionId(),
                fixture.contentRefId(), "{}", t0, t0, "EXTRACTION_FAILED", 1, null,
                t0.plusSeconds(30), false))).contains("llm");

        ExtractionConsumer retry = new ExtractionConsumer(cache, resolver,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC));
        retry.poll();

        org.mockito.Mockito.verify(resolver, times(1)).forTenant(tenant);
        org.mockito.Mockito.verify(resolver, times(1)).forProvider("llm");
        assertThat(cache.readExtractionStatus(tenant, CONNECTOR, SOURCE, RESOURCE,
                fixture.versionId())).contains("KNOWLEDGE_READY");
        assertThat(session.execute(
                "SELECT extraction_provider FROM ingestion_cache.source_versions " +
                        "WHERE tenant_id=? AND connector_id=? AND source_id=? AND resource_id=? " +
                        "AND version_id=?",
                tenant, CONNECTOR, SOURCE, RESOURCE, fixture.versionId())
                .one().getString("extraction_provider")).isEqualTo("llm");
    }

    @Test
    void firstAttemptPersistsProviderBeforeExtraction() {
        String tenant = "tenant-provider-before";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenAnswer(invocation -> {
            String persisted = session.execute(
                    "SELECT extraction_provider FROM ingestion_cache.source_versions " +
                            "WHERE tenant_id=? AND connector_id=? AND source_id=? AND resource_id=? " +
                            "AND version_id=?",
                    tenant, CONNECTOR, SOURCE, RESOURCE, fixture.versionId())
                    .one().getString("extraction_provider");
            assertThat(persisted).isEqualTo("llm");
            return new ExtractionResult(List.of(), List.of());
        });
        ResolvedExtractionAdapter llm = new ResolvedExtractionAdapter(
                adapter, ExtractionProvider.LLM, "tenant-override");
        ExtractionAdapterResolver resolver = new ExtractionAdapterResolver() {
            @Override
            public ResolvedExtractionAdapter forTenant(String tenantId) {
                return llm;
            }

            @Override
            public ResolvedExtractionAdapter forProvider(String provider) {
                return llm;
            }
        };

        new ExtractionConsumer(cache, resolver,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC()).poll();

        assertThat(cache.readExtractionStatus(tenant, CONNECTOR, SOURCE, RESOURCE,
                fixture.versionId())).contains("KNOWLEDGE_READY");
    }

    @Test
    void failedGraphCommitIsListedClaimableAndCompletable() {
        String tenant = "tenant-graph-retry";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        Instant now = Instant.now();
        var request = new org.synanton.ingestioncache.domain.ExtractionRequestRow(
                tenant, CONNECTOR, SOURCE, RESOURCE, UUID.randomUUID(), fixture.versionId(),
                fixture.contentRefId(), "{}", now, now, "KNOWLEDGE_READY", 0, null, null, false);
        cache.markExtractionSucceeded(request, now);
        cache.upsertExtractionResult(tenant, fixture.contentRefId(), 0, fixture.versionId(),
                "[]", "[]", now);
        cache.markGraphFailed(request, "FAILED", java.util.Map.of("relations_rejected", 1),
                1, now.plusSeconds(30), now);

        assertThat(cache.listPendingGraphCommits(tenant, 10, now.plusSeconds(31))).hasSize(1);
        assertThat(cache.claimGraphCommit(request)).isTrue();
        cache.markGraphCommitted(request, "MENTIONS_COMMITTED", java.util.Map.of("relations_accepted", 1),
                1, now.plusSeconds(32));
        assertThat(cache.listPendingGraphCommits(tenant, 10, now.plusSeconds(60))).isEmpty();
    }

    @Test
    void resolutionAndPromotionResultsRoundTrip() {
        String tenant = "tenant-resolution";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        Instant now = Instant.now();
        cache.upsertResolutionResult(new ResolutionResultRow(
                tenant, fixture.versionId(), "mention-1", fixture.contentRefId(), 0,
                0, 9, 100, 109, "Halcyon-7", "MaintenanceSystem", "RESOLVED", "RESOLVED_LABEL",
                "entity-halcyon-7", "Halcyon-7", 0.91, 0.22, "resolution-v1", "1.0.0",
                "{}", now, null));
        var resolutions = cache.listResolutionResults(tenant, fixture.versionId());
        assertThat(resolutions).hasSize(1);
        assertThat(resolutions.getFirst().outcome()).isEqualTo("RESOLVED");
        assertThat(resolutions.getFirst().bestScore()).isEqualTo(0.91);

        PromotionOwnershipStore ownership = new PromotionOwnershipStore(session);
        PromotionOwnershipStore.OwnershipToken token = ownership.acquire(tenant,
                fixture.versionId(), "test-seed").orElseThrow();
        ownership.insertPromotionResult(new RelationPromotionResultRow(
                tenant, fixture.versionId(), "relation-1", "mention-1", "mention-2", "requires",
                "PROMOTED", "entity-halcyon-7", "entity-helium", null, "{\"chunks\":[\"ref:0\"]}",
                "resolution-v1", "1.0.0", now, null), token);
        assertThat(cache.listRelationPromotionResults(tenant, fixture.versionId()))
                .singleElement()
                .satisfies(row -> assertThat(row.promotionStatus()).isEqualTo("PROMOTED"));

        cache.markResolutionStatus(tenant, CONNECTOR, SOURCE, RESOURCE, fixture.versionId(),
                "COMPLETED", now, now, java.util.Map.of("resolved", 1));
        assertThat(cache.readResolutionStatus(tenant, CONNECTOR, SOURCE, RESOURCE, fixture.versionId()))
                .contains("COMPLETED");
    }

    @Test
    void completeCacheHitSkipsAdapterAndDoesNotCreateOrPromoteAGeneration() {
        String tenant = "tenant-cache-hit";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        QuarantineStore quarantine = new QuarantineStore(session);
        ExtractionCacheStore cacheStore = new ExtractionCacheStore(session);
        ExtractionCacheService cacheService = new ExtractionCacheService(cacheStore,
                new ObjectMapper());
        UUID originalGeneration = UUID.randomUUID();
        quarantine.createGeneration(QuarantineGeneration.staged(tenant, fixture.versionId(),
                originalGeneration, "doc", "extraction-v2", "a".repeat(64), null,
                "qwen3-8b-fp8", "rev", Instant.now(), UUID.randomUUID()));
        quarantine.promote(tenant, fixture.versionId(), originalGeneration, "reviewer", "promote");
        ExtractionCacheKey key = ExtractionCacheKey.forRequest(tenant, fixture.versionId(),
                "Halcyon-7 requires a helium leak check.", "a".repeat(64), null,
                "qwen3-8b-fp8", "rev", 0.0, 1.0, "tok-v1");
        cacheService.put(key, originalGeneration, new ExtractionResult(
                List.of(new PreEntity("m1", "Halcyon-7", "MaintenanceSystem", 0, 9, "Halcyon-7")),
                List.of(), LlmUsage.NONE, List.of(), ExtractionIntegrity.CLEAN,
                new LlmProvenance("extraction-v2", "a".repeat(64), null, null, null)));
        AtomicInteger calls = new AtomicInteger();
        ExtractionEngineAdapter adapter = new ExtractionEngineAdapter() {
            @Override
            public Optional<ExtractionCacheKey> cacheKey(TextSegment segment,
                                                         ExtractionContext context) {
                return Optional.of(key);
            }

            @Override
            public ExtractionResult extract(TextSegment segment, ExtractionContext context) {
                calls.incrementAndGet();
                throw new AssertionError("cache hit must not invoke the adapter");
            }
        };

        new ExtractionConsumer(cache, adapter,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC(), quarantine, cacheService).poll();

        assertThat(calls).hasValue(0);
        assertThat(cache.readExtractionStatus(tenant, CONNECTOR, SOURCE, RESOURCE,
                fixture.versionId())).contains("KNOWLEDGE_READY");
        assertThat(quarantine.generations(tenant, fixture.versionId())).hasSize(1);
        assertThat(quarantine.currentGeneration(tenant, fixture.versionId()))
                .contains(originalGeneration);
        assertThat(cache.listExtractionResults(tenant, fixture.contentRefId()))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.entitiesJson()).contains("Halcyon-7");
                    assertThat(result.extractionGeneration()).isEqualTo(originalGeneration);
                });
    }

    @Test
    void ontologyReleaseIsPersistedOnGenerationAndLinkedFromResults() {
        String tenant = "tenant-ontology-provenance";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        QuarantineStore quarantine = new QuarantineStore(session);
        OntologyReleaseResolver resolver = tenantId -> Optional.of(
                new OntologyRelease("release-1", "d".repeat(64)));
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenReturn(new ExtractionResult(
                List.of(new PreEntity("m1", "Halcyon-7", "MaintenanceSystem", 0, 9, "Halcyon-7")),
                List.of(), LlmUsage.NONE, List.of(), ExtractionIntegrity.CLEAN,
                new LlmProvenance("extraction-v2", "a".repeat(64), null, null, null)));

        new ExtractionConsumer(cache, adapter,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC(), quarantine, null, resolver).poll();

        var generation = quarantine.generations(tenant, fixture.versionId()).getFirst();
        assertThat(generation.ontologyReleaseId()).isEqualTo("release-1");
        assertThat(generation.ontologyReleaseDigest()).isEqualTo("d".repeat(64));
        assertThat(cache.listExtractionResults(tenant, fixture.contentRefId()))
                .singleElement()
                .satisfies(result -> assertThat(result.extractionGeneration())
                        .isEqualTo(generation.extractionGeneration()));
    }

    @Test
    void extractionResultConsumerReceivesPinnedProvenance() {
        String tenant = "tenant-result-consumer";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        QuarantineStore quarantine = new QuarantineStore(session);
        OntologyReleaseResolver resolver = tenantId -> Optional.of(
                new OntologyRelease("release-1", "d".repeat(64)));
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenReturn(new ExtractionResult(
                List.of(new PreEntity("m1", "Halcyon-7", "MaintenanceSystem", 0, 9, "Halcyon-7")),
                List.of(), LlmUsage.NONE, List.of(), ExtractionIntegrity.CLEAN,
                new LlmProvenance("extraction-v2", "a".repeat(64), null, null, null)));
        GraphWriter writer = mock(GraphWriter.class);
        when(writer.commit(any(), any(), any(), any(Integer.class), any())).thenReturn(
                new GraphCommitResult(1, 0, 0, List.of(), "MENTIONS_COMMITTED"));

        new ExtractionConsumer(cache, adapter,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                writer, Clock.systemUTC(), quarantine, null, resolver).poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<Integer, ExtractionResult>> captured =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(writer).commit(any(), any(), captured.capture(), any(Integer.class), any());
        ExtractionResult consumed = captured.getValue().get(0);
        assertThat(consumed.preEntities()).hasSize(1);
        assertThat(consumed.provenance().promptVersion()).isEqualTo("extraction-v2");
        assertThat(consumed.provenance().promptCompiledDigest()).isEqualTo("a".repeat(64));
    }

    @Test
    void ingestionTriggerCarriesIdentityThroughGenerationReleaseAndResult() {
        String tenant = "tenant-trigger-provenance";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        QuarantineStore quarantine = new QuarantineStore(session);
        OntologyReleaseResolver resolver = tenantId -> Optional.of(
                new OntologyRelease("release-1", "d".repeat(64)));
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenReturn(new ExtractionResult(
                List.of(new PreEntity("m1", "Halcyon-7", "MaintenanceSystem", 0, 9, "Halcyon-7")),
                List.of(), LlmUsage.NONE, List.of(), ExtractionIntegrity.CLEAN,
                new LlmProvenance("extraction-v2", "a".repeat(64), null, null, null)));

        new ExtractionConsumer(cache, adapter,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC(), quarantine, null, resolver).poll();

        List<SourceOutboxRow> outbox = cache.listExtractionOutbox(
                tenant, CONNECTOR, SOURCE, RESOURCE);
        assertThat(outbox).singleElement()
                .satisfies(event -> assertThat(event.sourceVersionId())
                        .isEqualTo(fixture.versionId()));
        QuarantineGeneration generation = quarantine.generations(tenant, fixture.versionId())
                .getFirst();
        assertThat(generation.sourceVersionId()).isEqualTo(fixture.versionId());
        assertThat(generation.ontologyReleaseId()).isEqualTo("release-1");
        assertThat(cache.listExtractionResults(tenant, fixture.contentRefId()))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.sourceVersionId()).isEqualTo(fixture.versionId());
                    assertThat(result.extractionGeneration())
                            .isEqualTo(generation.extractionGeneration());
                });
    }

    @Test
    void persistedOntologyReleaseWinsOverAChangedResolverOnRetry() {
        String tenant = "tenant-ontology-retry";
        Fixture fixture = seed(tenant, "Halcyon-7 requires a helium leak check.");
        QuarantineStore quarantine = new QuarantineStore(session);
        ExtractionRequestRow request = new ExtractionRequestRow(
                tenant, CONNECTOR, SOURCE, RESOURCE, UUID.randomUUID(), fixture.versionId(),
                fixture.contentRefId(), "{}", Instant.now(), Instant.now(), "PENDING", 0,
                null, null, false);
        cache.markExtractionOntologyRelease(request,
                new OntologyReleaseRef("release-A", "a".repeat(64)));
        OntologyReleaseResolver changedResolver = tenantId -> Optional.of(
                new OntologyRelease("release-B", "b".repeat(64)));
        ExtractionEngineAdapter adapter = mock(ExtractionEngineAdapter.class);
        when(adapter.extract(any(), any())).thenReturn(new ExtractionResult(
                List.of(), List.of(), LlmUsage.NONE, List.of(), ExtractionIntegrity.CLEAN,
                new LlmProvenance("extraction-v2", "a".repeat(64), null, null, null)));

        new ExtractionConsumer(cache, adapter,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC(), quarantine, null,
                changedResolver).poll();

        assertThat(quarantine.generations(tenant, fixture.versionId())).singleElement()
                .satisfies(generation -> {
                    assertThat(generation.ontologyReleaseId()).isEqualTo("release-A");
                    assertThat(generation.ontologyReleaseDigest()).isEqualTo("a".repeat(64));
                });
    }

    @Test
    void cacheMissRunsNormalLifecycleAndStoresTheNewResult() {
        String tenant = "tenant-cache-miss";
        String text = "Halcyon-7 requires a helium leak check.";
        Fixture fixture = seed(tenant, text);
        QuarantineStore quarantine = new QuarantineStore(session);
        ExtractionCacheStore cacheStore = new ExtractionCacheStore(session);
        ExtractionCacheService cacheService = new ExtractionCacheService(cacheStore,
                new ObjectMapper());
        ExtractionCacheKey key = ExtractionCacheKey.forRequest(tenant, fixture.versionId(), text,
                "a".repeat(64), null, "qwen3-8b-fp8", "rev", 0.0, 1.0, "tok-v1");
        AtomicInteger calls = new AtomicInteger();
        ExtractionEngineAdapter adapter = new ExtractionEngineAdapter() {
            @Override
            public Optional<ExtractionCacheKey> cacheKey(TextSegment segment,
                                                         ExtractionContext context) {
                return Optional.of(key);
            }

            @Override
            public ExtractionResult extract(TextSegment segment, ExtractionContext context) {
                calls.incrementAndGet();
                return new ExtractionResult(
                        List.of(new PreEntity("m1", "Halcyon-7", "MaintenanceSystem", 0, 9,
                                "Halcyon-7")),
                        List.of(), LlmUsage.NONE, List.of(), ExtractionIntegrity.CLEAN,
                        new LlmProvenance("extraction-v2", "a".repeat(64), null, null, null));
            }
        };

        new ExtractionConsumer(cache, adapter,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC(), quarantine, cacheService).poll();

        assertThat(calls).hasValue(1);
        assertThat(cache.readExtractionStatus(tenant, CONNECTOR, SOURCE, RESOURCE,
                fixture.versionId())).contains("KNOWLEDGE_READY");
        assertThat(quarantine.generations(tenant, fixture.versionId())).hasSize(1);
        UUID generation = quarantine.generations(tenant, fixture.versionId()).getFirst()
                .extractionGeneration();
        assertThat(cacheService.lookup(key)).get()
                .satisfies(hit -> assertThat(hit.generationId()).isEqualTo(generation));
    }

    private static ExtractionConsumer consumer(String tenant, ExtractionEngineAdapter adapter) {
        return new ExtractionConsumer(cache, adapter,
                new ExtractionConsumerProperties(true, tenant, 1, 5, 30, 5000L),
                mock(GraphWriter.class), Clock.systemUTC());
    }

    private static UUID latestVersion(String tenant) {
        return cache.listSourceVersions(tenant, CONNECTOR, SOURCE, RESOURCE).getFirst().versionId();
    }

    private static Fixture seed(String tenant, String text) {
        Instant now = Instant.now();
        cache.createSourceIfAbsent(new SourceRow(tenant, SOURCE, CONNECTOR, "upload",
                "upload://" + SOURCE, Set.of(), "ACTIVE", now, null));
        cache.createSourceIdentityIfAbsent(new SourceIdentityRow(tenant, CONNECTOR, SOURCE, RESOURCE));
        UUID contentRef = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SourceVersionRow version = new SourceVersionRow(tenant, CONNECTOR, SOURCE, RESOURCE, versionId,
                "sha", "rev", now, contentRef, UUID.randomUUID(), "");
        SourceOutboxRow sourceEvent = new SourceOutboxRow(tenant, CONNECTOR, SOURCE, RESOURCE,
                UUID.randomUUID(), "SourcePublished", versionId, null, "{}", now, false);
        SourceOutboxRow extractionEvent = new SourceOutboxRow(tenant, CONNECTOR, SOURCE, RESOURCE,
                UUID.randomUUID(), "ExtractionRequested", versionId, null, "{}", now, false);
        assertThat(cache.insertSourceVersionAndOutboxIfAbsent(version, sourceEvent, extractionEvent)).isTrue();
        cache.markSourceSearchProjected(sourceEvent, now);
        cache.insertChunks(List.of(new ChunkRow(tenant, contentRef, 0, text, "sha0")));
        return new Fixture(contentRef, versionId);
    }

    private record Fixture(UUID contentRefId, UUID versionId) {}
}
