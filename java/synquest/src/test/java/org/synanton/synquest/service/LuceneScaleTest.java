package org.synanton.synquest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synquest.config.SynquestProperties;
import org.synanton.synquest.engine.lucene.HybridSearcher;
import org.synanton.synquest.engine.lucene.LuceneIndexBuilder;

/**
 * Scale guard for the streaming projection: 1,200 chunks must build without
 * exhausting the heap, land as exactly 1,200 documents, and be searchable.
 */
class LuceneScaleTest {

    private static final String TENANT = "tenant-scale";
    private static final String MODEL = "bge-base-en-v1.5";
    private static final int DIM = 768;
    private static final int CHUNKS = 1_200;

    @TempDir Path indexRoot;
    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final UUID contentRef = UUID.randomUUID();

    @BeforeEach
    void seed() {
        CacheCursorStubs.wire(cache);
        when(cache.listManifest(eq(TENANT), anyInt())).thenReturn(List.of(manifest()));
        List<ChunkRow> chunks = new ArrayList<>(CHUNKS);
        for (int ordinal = 0; ordinal < CHUNKS; ordinal++) {
            chunks.add(new ChunkRow(TENANT, contentRef, ordinal,
                    "scale chunk " + ordinal + " cargo manifest line " + (ordinal % 17), "sha-" + ordinal));
        }
        when(cache.readChunks(TENANT, contentRef)).thenReturn(chunks);
        when(cache.readEmbedding(eq(TENANT), any(), anyInt(), eq(MODEL))).thenReturn(Optional.empty());
    }

    @Test
    void buildsAndSearchesOneThousandPlusChunks() throws Exception {
        SynquestProperties props = new SynquestProperties(
                new SynquestProperties.Index(indexRoot.toString(), false, 30), null,
                new SynquestProperties.Embedding(MODEL, DIM, true, 0), null);
        LuceneIndexBuilder builder = new LuceneIndexBuilder(cache, props);

        builder.build(TENANT);

        assertThat(builder.lastReport(TENANT).orElseThrow().docs()).isEqualTo(CHUNKS);
        try (HybridSearcher searcher = new HybridSearcher(builder.indexPath(TENANT), DIM)) {
            assertThat(searcher.docCount()).isEqualTo(CHUNKS);
            assertThat(searcher.lexical("chunk 777", 10).scoreDocs).isNotEmpty();
        }
    }

    private ManifestRow manifest() {
        return new ManifestRow(TENANT, contentRef, Instant.now(), 1, "fixed-window", 1, "CHUNKED", "CONTENT_CACHE",
                "", "file:///" + contentRef, "sha", 10, "text/plain", "FULL", null, "", null);
    }
}
