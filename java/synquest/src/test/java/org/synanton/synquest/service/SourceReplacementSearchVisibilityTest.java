package org.synanton.synquest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.KnowledgeProcessingRunRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.synquest.config.SynquestProperties;
import org.synanton.synquest.engine.lucene.LuceneIndexBuilder;
import org.synanton.synquest.service.KnowledgeSearchBackend.KnowledgeCandidate;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #694a / #656 acceptance harness: consumer-visible search follows the
 * authoritative current revision while preserving genuinely shared and
 * tenant-isolated knowledge.
 *
 * <p>The index is an immutable projection. Visibility is resolved at query time
 * from each chunk's supporting source versions and the source's
 * {@code source_current.active_revision_id}; no activation-time index mutation is
 * required. The red baseline for this file is commit {@code b6fcff8f}.
 */
class SourceReplacementSearchVisibilityTest {

    private static final String CONNECTOR = "upload";
    private static final String RESOURCE = "directive.txt";

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
    void searchVisibility_afterSourceReplacement(@TempDir Path indexDir) throws Exception {
        Harness h = harness(indexDir, "tenant-654-replacement");
        String source = "source-replacement";
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID contentRef1 = UUID.randomUUID();
        UUID contentRef2 = UUID.randomUUID();
        Instant now = Instant.now();

        insertRevision(h, source, v1, contentRef1, 1, "alpha fact from revision one", null, now);
        insertRevision(h, source, v2, contentRef2, 2, "beta fact from revision two", v1, now.plusSeconds(1));

        makeCurrent(h, source, v1, 1, now);
        project(h);
        assertThat(candidates(h, "alpha"))
                .as("V1 fact must be searchable before replacement")
                .extracting(KnowledgeCandidate::contentRefId)
                .contains(contentRef1);

        makeCurrent(h, source, v2, 2, now.plusSeconds(2));
        project(h);
        assertThat(cache.readCurrentRevisionId(h.tenant, source))
                .as("the read boundary must see V2 as current")
                .contains(v2);

        assertThat(candidates(h, "beta"))
                .as("V2 fact must be searchable after replacement")
                .extracting(KnowledgeCandidate::contentRefId)
                .contains(contentRef2);
        assertThat(candidates(h, "alpha"))
                .as("V1-only content must disappear after V2 becomes current")
                .extracting(KnowledgeCandidate::contentRefId)
                .doesNotContain(contentRef1);
    }

    @Test
    void retiredOnlyFactDisappearsAfterReplacement(@TempDir Path indexDir) throws Exception {
        Harness h = harness(indexDir, "tenant-654-retired");
        String source = "source-retired";
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID contentRef1 = UUID.randomUUID();
        UUID contentRef2 = UUID.randomUUID();
        Instant now = Instant.now();

        insertRevision(h, source, v1, contentRef1, 1, "retired-only alpha fact", null, now);
        insertRevision(h, source, v2, contentRef2, 2, "unrelated revision two", v1, now.plusSeconds(1));

        makeCurrent(h, source, v1, 1, now);
        project(h);
        assertThat(candidates(h, "alpha"))
                .extracting(KnowledgeCandidate::contentRefId)
                .contains(contentRef1);

        makeCurrent(h, source, v2, 2, now.plusSeconds(2));
        project(h);
        assertThat(candidates(h, "alpha"))
                .as("a fact supported only by the retired revision must disappear")
                .isEmpty();
    }

    @Test
    void sharedFactRemainsWhenAnotherSourceStillSupportsIt(@TempDir Path indexDir) throws Exception {
        Harness h = harness(indexDir, "tenant-654-multi-source");
        String sourceA = "source-a";
        String sourceB = "source-b";
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID b1 = UUID.randomUUID();
        UUID contentRefA1 = UUID.randomUUID();
        UUID contentRefA2 = UUID.randomUUID();
        UUID contentRefB1 = UUID.randomUUID();
        Instant now = Instant.now();

        insertRevision(h, sourceA, a1, contentRefA1, 1, "shared alpha fact from source A", null, now);
        insertRevision(h, sourceB, b1, contentRefB1, 1, "shared alpha fact from source B", null, now);
        makeCurrent(h, sourceA, a1, 1, now);
        makeCurrent(h, sourceB, b1, 1, now);
        project(h);
        assertThat(candidates(h, "alpha"))
                .extracting(KnowledgeCandidate::contentRefId)
                .contains(contentRefA1, contentRefB1);

        insertRevision(h, sourceA, a2, contentRefA2, 2, "replacement without alpha", a1, now.plusSeconds(1));
        makeCurrent(h, sourceA, a2, 2, now.plusSeconds(2));
        project(h);

        List<UUID> visible = candidates(h, "alpha").stream()
                .map(KnowledgeCandidate::contentRefId)
                .toList();
        assertThat(visible)
                .as("source B still supports the shared assertion")
                .contains(contentRefB1)
                .doesNotContain(contentRefA1);
    }

    @Test
    void tenantIsolationHoldsUnderReplacement(@TempDir Path indexDir) throws Exception {
        Harness tenantA = harness(indexDir.resolve("a"), "tenant-654-isolation-a");
        Harness tenantB = harness(indexDir.resolve("b"), "tenant-654-isolation-b");
        Instant now = Instant.now();
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID b1 = UUID.randomUUID();
        UUID contentRefA1 = UUID.randomUUID();
        UUID contentRefA2 = UUID.randomUUID();
        UUID contentRefB1 = UUID.randomUUID();

        insertRevision(tenantA, "source-a", a1, contentRefA1, 1, "alpha secret from tenant A", null, now);
        insertRevision(tenantB, "source-b", b1, contentRefB1, 1, "alpha fact from tenant B", null, now);
        makeCurrent(tenantA, "source-a", a1, 1, now);
        makeCurrent(tenantB, "source-b", b1, 1, now);
        project(tenantA);
        project(tenantB);

        assertThat(candidates(tenantB, "alpha"))
                .extracting(KnowledgeCandidate::contentRefId)
                .contains(contentRefB1)
                .doesNotContain(contentRefA1);

        insertRevision(tenantA, "source-a", a2, contentRefA2, 2, "replacement for tenant A", a1,
                now.plusSeconds(1));
        makeCurrent(tenantA, "source-a", a2, 2, now.plusSeconds(2));
        project(tenantA);

        assertThat(candidates(tenantB, "alpha"))
                .as("tenant B must not observe tenant A's replacement state")
                .extracting(KnowledgeCandidate::contentRefId)
                .contains(contentRefB1)
                .doesNotContain(contentRefA1, contentRefA2);
    }

    @Test
    void partialActivationKeepsOldRevisionAuthoritative(@TempDir Path indexDir) throws Exception {
        Harness h = harness(indexDir, "tenant-654-partial");
        String source = "source-partial";
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID contentRef1 = UUID.randomUUID();
        UUID contentRef2 = UUID.randomUUID();
        Instant now = Instant.now();

        insertRevision(h, source, v1, contentRef1, 1, "alpha fact before activation", null, now);
        insertRevision(h, source, v2, contentRef2, 2, "beta fact awaiting activation", v1, now.plusSeconds(1));

        // V2 is indexed but not current: the old revision remains authoritative.
        makeCurrent(h, source, v1, 1, now);
        project(h);
        assertThat(candidates(h, "alpha"))
                .extracting(KnowledgeCandidate::contentRefId)
                .contains(contentRef1);
        assertThat(candidates(h, "beta"))
                .as("the replacement facts must not leak before activation")
                .isEmpty();

        // Activation flips the pointer; the same index now exposes V2 and hides V1.
        makeCurrent(h, source, v2, 2, now.plusSeconds(2));
        assertThat(candidates(h, "beta"))
                .extracting(KnowledgeCandidate::contentRefId)
                .contains(contentRef2);
        assertThat(candidates(h, "alpha"))
                .extracting(KnowledgeCandidate::contentRefId)
                .doesNotContain(contentRef1);
    }

    private static List<KnowledgeCandidate> candidates(Harness h, String text) throws Exception {
        return h.backend.candidates(h.tenant, text, 10);
    }

    private static void project(Harness h) throws Exception {
        h.projection.projectNewSourceVersions();
        h.search.initTenant(h.tenant);
    }

    private static void insertRevision(Harness h, String source, UUID versionId, UUID contentRefId,
                                       long generation, String text, UUID previousVersionId,
                                       Instant observedAt) {
        cache.createSourceIfAbsent(new SourceRow(h.tenant, source, CONNECTOR, "upload",
                "upload://" + source, Set.of(), "ACTIVE", observedAt, null));
        cache.createSourceIdentityIfAbsent(new SourceIdentityRow(h.tenant, CONNECTOR, source, RESOURCE));

        UUID processingRunId = UUID.randomUUID();
        cache.upsertKnowledgeProcessingRun(new KnowledgeProcessingRunRow(
                h.tenant, processingRunId, "synflux-knowledge", "semantic-v1:1",
                "{}", "{}", null, "COMPLETED", observedAt, observedAt, null, null, Set.of()));
        cache.upsertManifest(new ManifestRow(
                h.tenant, contentRefId, observedAt, 1, "semantic-v1", 1, "EMBEDDED", "CONTENT_CACHE",
                "", "upload://" + source + "/" + RESOURCE, "sha256:" + contentRefId, 128, "text/plain",
                "FULL", null, "", null, processingRunId));

        SourceVersionRow version = new SourceVersionRow(
                h.tenant, CONNECTOR, source, RESOURCE, versionId, "sha256:" + contentRefId,
                "rev-" + generation, observedAt, contentRefId, UUID.randomUUID(), "",
                generation, "sha256:" + contentRefId, previousVersionId);
        cache.insertChunks(List.of(new ChunkRow(
                h.tenant, contentRefId, 0, text, "sha256:chunk-" + contentRefId,
                1, 1, "", "", "", "[]", 0, "", false, ChunkRow.PUBLIC_ONLY,
                "", "", 0, Set.of(versionId), processingRunId, -1, -1)));
        SourceOutboxRow event = new SourceOutboxRow(
                h.tenant, CONNECTOR, source, RESOURCE, UUID.randomUUID(), "SourcePublished",
                versionId, null, "{}", observedAt, false);
        assertThat(cache.insertSourceVersionAndOutboxIfAbsent(version, event)).isTrue();
    }

    private static void makeCurrent(Harness h, String source, UUID revisionId, long generation, Instant now) {
        session.execute(SimpleStatement.newInstance(
                "UPDATE ingestion_cache.source_current SET accepted_generation=?, current_generation=?, "
                        + "active_revision_id=?, pending_generation=null, pending_revision_id=null, updated_at=? "
                        + "WHERE tenant_id=? AND source_id=?",
                generation, generation, revisionId, now, h.tenant, source));
    }

    private static Harness harness(Path indexDir, String tenant) {
        SynquestProperties props = new SynquestProperties(
                new SynquestProperties.Index(indexDir.toString(), false, 30),
                null,
                new SynquestProperties.Embedding("bge-base-en-v1.5", 768, true, 0),
                null);
        LuceneIndexBuilder builder = new LuceneIndexBuilder(cache, props);
        SearchService search = new SearchService(builder, mock(QueryEmbedder.class), props);
        return new Harness(tenant, search,
                new LuceneKnowledgeSearchBackend(builder, search, cache, props),
                new SearchProjectionConsumer(cache, search, tenant, 200, true));
    }

    private record Harness(String tenant, SearchService search,
                           LuceneKnowledgeSearchBackend backend,
                           SearchProjectionConsumer projection) {}
}
