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
import org.synanton.synquest.search.SearchDocument;
import org.synanton.synquest.search.SearchFilter;
import org.synanton.synquest.search.SearchQuery;
import org.synanton.synquest.search.SearchResult;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * #694 / #656 acceptance harness: a consumer-visible search result must follow
 * the authoritative current revision.
 *
 * <p>This is intentionally expected to fail on the current tree. The search
 * projection is keyed by {@code content_ref_id}; a replacement revision has a
 * new content-addressed ref, and the {@code SourcePublished} projection only
 * upserts the new ref. Nothing removes V1's chunks when V2 becomes current, so
 * V1-only facts remain searchable.
 */
class SourceReplacementSearchVisibilityTest {

    private static final String TENANT = "tenant-654-read-visibility";
    private static final String CONNECTOR = "upload";
    private static final String SOURCE = "source-654-read-visibility";
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
        Instant now = Instant.now();
        UUID version1 = UUID.randomUUID();
        UUID version2 = UUID.randomUUID();
        UUID contentRef1 = UUID.randomUUID();
        UUID contentRef2 = UUID.randomUUID();

        cache.createSourceIfAbsent(new SourceRow(TENANT, SOURCE, CONNECTOR, "upload",
                "upload://" + SOURCE, Set.of(), "ACTIVE", now, null));
        cache.createSourceIdentityIfAbsent(new SourceIdentityRow(TENANT, CONNECTOR, SOURCE, RESOURCE));

        insertRevision(version1, contentRef1, 1, "alpha fact from revision one", null, now);
        insertRevision(version2, contentRef2, 2, "beta fact from revision two", version1, now.plusSeconds(1));

        SynquestProperties props = new SynquestProperties(
                new SynquestProperties.Index(indexDir.toString(), false, 30),
                null,
                new SynquestProperties.Embedding("bge-base-en-v1.5", 768, true, 0),
                null);
        LuceneIndexBuilder builder = new LuceneIndexBuilder(cache, props);
        SearchService search = new SearchService(builder, mock(QueryEmbedder.class), props);
        SearchProjectionConsumer projection = new SearchProjectionConsumer(cache, search, TENANT, 200, true);

        // V1 is current and searchable.
        makeCurrent(version1, 1, now);
        projection.projectNewSourceVersions();
        search.initTenant(TENANT);
        assertThat(search("alpha", search))
                .as("V1 fact must be searchable before replacement")
                .extracting(SearchDocument::contentRefId)
                .contains(contentRef1);

        // V2 replaces V1 and is the authoritative current revision before the read.
        makeCurrent(version2, 2, now.plusSeconds(2));
        projection.projectNewSourceVersions();
        search.initTenant(TENANT);
        assertThat(cache.readCurrentRevisionId(TENANT, SOURCE))
                .as("the read boundary must see V2 as current")
                .contains(version2);

        assertThat(search("beta", search))
                .as("V2 fact must be searchable after replacement")
                .extracting(SearchDocument::contentRefId)
                .contains(contentRef2);

        // The read path must not return V1-only content once V2 is current.
        // This is the expected failure: the index still holds V1's content ref.
        assertThat(search("alpha", search))
                .as("V1-only content must disappear after V2 becomes current")
                .extracting(SearchDocument::contentRefId)
                .doesNotContain(contentRef1);
    }

    private static List<SearchDocument> search(String text, SearchService search) throws Exception {
        SearchResult result = search.search(
                new SearchQuery(text, 10, 0, 10, 60, false, 0, null, null, "parallel"),
                new SearchFilter(TENANT));
        return result.documents();
    }

    private static void insertRevision(UUID versionId, UUID contentRefId, long generation,
                                       String text, UUID previousVersionId, Instant observedAt) {
        UUID processingRunId = UUID.randomUUID();
        cache.upsertKnowledgeProcessingRun(new KnowledgeProcessingRunRow(
                TENANT, processingRunId, "synflux-knowledge", "semantic-v1:1",
                "{}", "{}", null, "COMPLETED", observedAt, observedAt, null, null, Set.of()));
        cache.upsertManifest(new ManifestRow(
                TENANT, contentRefId, observedAt, 1, "semantic-v1", 1, "EMBEDDED", "CONTENT_CACHE",
                "", "upload://" + SOURCE + "/" + RESOURCE, "sha256:" + contentRefId, 128, "text/plain",
                "FULL", null, "", null, processingRunId));
        cache.insertChunks(List.of(new ChunkRow(
                TENANT, contentRefId, 0, text, "sha256:chunk-" + contentRefId,
                1, 1, "", "", "")));

        SourceVersionRow version = new SourceVersionRow(
                TENANT, CONNECTOR, SOURCE, RESOURCE, versionId, "sha256:" + contentRefId,
                "rev-" + generation, observedAt, contentRefId, UUID.randomUUID(), "",
                generation, "sha256:" + contentRefId, previousVersionId);
        SourceOutboxRow event = new SourceOutboxRow(
                TENANT, CONNECTOR, SOURCE, RESOURCE, UUID.randomUUID(), "SourcePublished",
                versionId, null, "{}", observedAt, false);
        assertThat(cache.insertSourceVersionAndOutboxIfAbsent(version, event)).isTrue();
    }

    private static void makeCurrent(UUID revisionId, long generation, Instant now) {
        session.execute(SimpleStatement.newInstance(
                "UPDATE ingestion_cache.source_current SET accepted_generation=?, current_generation=?, "
                        + "active_revision_id=?, pending_generation=null, pending_revision_id=null, updated_at=? "
                        + "WHERE tenant_id=? AND source_id=?",
                generation, generation, revisionId, now, TENANT, SOURCE));
    }
}
