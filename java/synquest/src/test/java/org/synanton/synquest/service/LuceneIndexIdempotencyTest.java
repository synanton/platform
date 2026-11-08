package org.synanton.synquest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.ingestioncache.domain.KnowledgeProcessingRunRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.synquest.config.SynquestProperties;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;
import org.synanton.synquest.engine.lucene.LuceneIndexBuilder;

/**
 * #536: the search projection is a full rebuild ({@link LuceneIndexBuilder} opens
 * the writer in {@code OpenMode.CREATE}), so an at-least-once outbox delivery
 * cannot duplicate Lucene documents. This test reproduces the failure mode:
 * project once, reset the outbox row to unpublished (crash before acknowledgement),
 * project again, and assert the index holds exactly the chunk count — not 2N.
 *
 * <p>If {@code build()} is ever changed to append, this test fails: the second
 * projection would double the document count.
 */
class LuceneIndexIdempotencyTest {

    private static final String TENANT = "tenant-idempotency";
    private static final String CONNECTOR = "upload";
    private static final String SOURCE = "idempotency-source";
    private static final String RESOURCE = "docs/duplicate-delivery.txt";
    private static final String MODEL = "bge-base-en-v1.5";
    private static final int DIM = 768;
    private static final int CHUNKS = 3;

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
    void reprojectingAfterAnUnacknowledgedDeliveryDoesNotDuplicateDocuments(@TempDir Path indexDir)
            throws Exception {
        Instant now = Instant.now();
        UUID contentRefId = UUID.randomUUID();
        UUID processingRunId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        cache.createSourceIfAbsent(new SourceRow(TENANT, SOURCE, CONNECTOR, "upload",
                "upload://" + SOURCE, Set.of(), "ACTIVE", now, null));
        cache.createSourceIdentityIfAbsent(new SourceIdentityRow(TENANT, CONNECTOR, SOURCE, RESOURCE));
        cache.upsertKnowledgeProcessingRun(new KnowledgeProcessingRunRow(
                TENANT, processingRunId, "synflux-knowledge", "semantic-v1:1",
                "{}", "{}", null, "COMPLETED", now, now, null, null, Set.of()));
        cache.upsertManifest(new ManifestRow(
                TENANT, contentRefId, now, 1, "semantic-v1", 1, "EMBEDDED", "CONTENT_CACHE",
                "", "upload://" + SOURCE + "/" + RESOURCE, "sha256:doc", 128, "text/plain",
                "FULL", null, "", null, processingRunId));

        List<ChunkRow> chunks = new ArrayList<>();
        for (int ordinal = 0; ordinal < CHUNKS; ordinal++) {
            String sha = "sha256:chunk-" + ordinal;
            chunks.add(new ChunkRow(TENANT, contentRefId, ordinal, "duplicate delivery chunk " + ordinal,
                    sha, 1, 1, "", "", ""));
            cache.upsertEmbedding(new EmbeddingRow(TENANT, contentRefId, ordinal, MODEL, sha,
                    unitVector(DIM), DIM, now));
        }
        cache.insertChunks(chunks);

        SourceVersionRow version = new SourceVersionRow(TENANT, CONNECTOR, SOURCE, RESOURCE, versionId,
                "sha256:doc", "rev-1", now, contentRefId, UUID.randomUUID(), "");
        SourceOutboxRow event = new SourceOutboxRow(TENANT, CONNECTOR, SOURCE, RESOURCE,
                UUID.randomUUID(), "SourcePublished", versionId, null, "{}", now, false);
        assertThat(cache.insertSourceVersionAndOutboxIfAbsent(version, event)).isTrue();

        SynquestProperties props = new SynquestProperties(
                new SynquestProperties.Index(indexDir.toString(), false, 30),
                null,
                new SynquestProperties.Embedding(MODEL, DIM, true, 0),
                null);
        LuceneIndexBuilder builder = new LuceneIndexBuilder(cache, props);
        SearchService search = new SearchService(builder, mock(QueryEmbedder.class), props);
        SearchProjectionConsumer consumer = new SearchProjectionConsumer(cache, search, TENANT, 200, true);

        consumer.projectNewSourceVersions();
        assertThat(indexedIds(builder.indexPath(TENANT))).hasSize(CHUNKS);
        assertThat(cache.readSearchProjectedAt(TENANT, CONNECTOR, SOURCE, RESOURCE, versionId))
                .as("extraction readiness is written after the Lucene projection")
                .isPresent();

        // Crash before acknowledgement: the event is durable but still unpublished.
        session.execute(SimpleStatement.newInstance(
                "UPDATE ingestion_cache.source_versions SET event_published=false, event_published_at=null "
                        + "WHERE tenant_id=? AND connector_id=? AND source_id=? AND resource_id=? AND version_id=?",
                TENANT, CONNECTOR, SOURCE, RESOURCE, versionId));

        consumer.projectNewSourceVersions();

        List<String> ids = indexedIds(builder.indexPath(TENANT));
        assertThat(ids).hasSize(CHUNKS);
        assertThat(ids).doesNotHaveDuplicates();
    }

    private static List<String> indexedIds(Path indexDir) throws Exception {
        try (FSDirectory dir = FSDirectory.open(indexDir);
             DirectoryReader reader = DirectoryReader.open(dir)) {
            List<String> ids = new ArrayList<>();
            for (int doc = 0; doc < reader.numDocs(); doc++) {
                ids.add(reader.storedFields().document(doc).get("id"));
            }
            return ids;
        }
    }

    private static float[] unitVector(int dim) {
        float[] vector = new float[dim];
        vector[0] = 1.0f;
        return vector;
    }
}
