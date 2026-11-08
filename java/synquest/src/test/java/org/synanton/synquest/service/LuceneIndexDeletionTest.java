package org.synanton.synquest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synquest.config.SynquestProperties;
import org.synanton.synquest.engine.lucene.LuceneIndexBuilder;

/** Incremental source deletion: delete one content ref, keep the rest of the tenant. */
class LuceneIndexDeletionTest {

    private static final String TENANT = "tenant-deletion";
    private static final String MODEL = "bge-base-en-v1.5";
    @TempDir Path indexRoot;
    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final UUID refA = UUID.randomUUID();
    private final UUID refB = UUID.randomUUID();

    @BeforeEach
    void seed() {
        CacheCursorStubs.wire(cache);
        when(cache.listManifest(eq(TENANT), anyInt()))
                .thenReturn(List.of(manifest(refA), manifest(refB)));
        when(cache.readChunks(TENANT, refA)).thenReturn(List.of(
                new ChunkRow(TENANT, refA, 0, "alpha one", "s0"),
                new ChunkRow(TENANT, refA, 1, "alpha two", "s1")));
        when(cache.readChunks(TENANT, refB)).thenReturn(List.of(
                new ChunkRow(TENANT, refB, 0, "beta one", "s2")));
        when(cache.readEmbedding(eq(TENANT), org.mockito.ArgumentMatchers.any(),
                anyInt(), eq(MODEL))).thenReturn(Optional.empty());
    }

    @Test
    void deletingOneContentRefKeepsTheRestOfTheTenant() throws Exception {
        SynquestProperties props = new SynquestProperties(
                new SynquestProperties.Index(indexRoot.toString(), false, 30), null,
                new SynquestProperties.Embedding(MODEL, 768, true, 0), null);
        LuceneIndexBuilder builder = new LuceneIndexBuilder(cache, props);

        builder.build(TENANT);
        assertThat(builder.lastReport(TENANT).orElseThrow().docs()).isEqualTo(3);

        builder.deleteContentRefs(TENANT, List.of(refA));

        assertThat(indexedIds(builder.indexPath(TENANT))).containsExactly(refB + ":0");
    }

    private static ManifestRow manifest(UUID ref) {
        return new ManifestRow(TENANT, ref, Instant.now(), 1, "fixed-window", 1, "CHUNKED", "CONTENT_CACHE",
                "", "file:///" + ref, "sha", 10, "text/plain", "FULL", null, "", null);
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
}
