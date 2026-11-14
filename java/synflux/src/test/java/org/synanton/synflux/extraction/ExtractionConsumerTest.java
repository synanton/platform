package org.synanton.synflux.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.ingestioncache.claim.PromotionOwnershipStore;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.ingestioncache.domain.ResolutionResultRow;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.synflux.config.ExtractionConsumerProperties;
import org.synanton.synflux.extraction.graph.GraphWriter;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

class ExtractionConsumerTest {

    private static final String CONNECTOR = "upload";
    private static final String SOURCE = "extraction-source";
    private static final String RESOURCE = "docs/halcyon.txt";

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static IngestionCacheClient cache;

    @BeforeAll
    static void startCassandra() {
        cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
        cassandra.start();
        InetSocketAddress contact = new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042));
        try (CqlSession admin = CqlSession.builder()
                .addContactPoint(contact).withLocalDatacenter("datacenter1").build()) {
            admin.execute("CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                    + "{'class':'SimpleStrategy','replication_factor':1}");
        }
        session = CqlSession.builder().addContactPoint(contact)
                .withLocalDatacenter("datacenter1").withKeyspace("ingestion_cache").build();
        SchemaInstaller.install(session);
        cache = new IngestionCacheClient(session);
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
