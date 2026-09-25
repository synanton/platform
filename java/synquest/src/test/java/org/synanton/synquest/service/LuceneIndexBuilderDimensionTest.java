package org.synanton.synquest.service;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synquest.config.SynquestProperties;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Index build: truncation of stored native vectors, build report, fail-closed when required. */
class LuceneIndexBuilderDimensionTest {

    private static final String MODEL = "synanton-free-embedding";
    private static final String TENANT = "rb-fixed-g";

    @TempDir Path indexRoot;

    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final UUID ref = UUID.randomUUID();

    private SynquestProperties props(int dim, int truncateDim) {
        return new SynquestProperties(new SynquestProperties.Index(indexRoot.toString(), true, 30), null,
                new SynquestProperties.Embedding(MODEL, dim, true, truncateDim));
    }

    /** Three chunks: ordinal 0 and 1 carry 2048-dim vectors, ordinal 2 has no vector. */
    private void corpus() {
        when(cache.listManifest(eq(TENANT), anyInt())).thenReturn(List.of(new ManifestRow(TENANT, ref, Instant.now(), 1,
                "structure-aware", 1, "EMBEDDED", "hot", null, "file:///a.md", "sha", 10, "text/markdown", null, null, null)));
        when(cache.readChunks(TENANT, ref)).thenReturn(List.of(
                new ChunkRow(TENANT, ref, 0, "alpha", "s0"),
                new ChunkRow(TENANT, ref, 1, "beta", "s1"),
                new ChunkRow(TENANT, ref, 2, "gamma", "s2")));
        for (int i = 0; i < 2; i++) {
            float[] v = new float[2048];
            v[i] = 1f;
            when(cache.readEmbedding(TENANT, ref, i, MODEL))
                    .thenReturn(Optional.of(new EmbeddingRow(TENANT, ref, i, MODEL, "s" + i, v, 2048, Instant.now())));
        }
        when(cache.readEmbedding(TENANT, ref, 2, MODEL)).thenReturn(Optional.empty());
    }

    private int indexedVectorDim() throws Exception {
        try (var dir = FSDirectory.open(indexRoot.resolve(TENANT)); var reader = DirectoryReader.open(dir)) {
            for (LeafReaderContext leaf : reader.leaves()) {
                FloatVectorValues values = leaf.reader().getFloatVectorValues("embedding");
                if (values != null) return values.dimension();
            }
        }
        return -1;
    }

    @Test
    void storedNativeVectorsAreTruncatedAndReported() throws Exception {
        corpus();
        var p = props(1024, 1024);
        var builder = new LuceneIndexBuilder(cache, p, new EmbeddingShape(p), false);

        builder.build(TENANT);

        var report = builder.lastReport(TENANT).orElseThrow();
        assertThat(report.docs()).isEqualTo(3);
        assertThat(report.vectorDocs()).isEqualTo(2);
        assertThat(report.dimMismatches()).isZero();
        assertThat(report.missingVectors()).isEqualTo(1);
        assertThat(report.embeddingDim()).isEqualTo(1024);
        assertThat(report.truncated()).isTrue();
        assertThat(indexedVectorDim()).isEqualTo(1024);
    }

    @Test
    void withoutTruncationNativeVectorsAreCountedAsMismatches() throws Exception {
        corpus();
        var p = props(768, 0);
        var builder = new LuceneIndexBuilder(cache, p, new EmbeddingShape(p), false);

        builder.build(TENANT); // legacy (not required): builds lexical-only, but the report says so

        var report = builder.lastReport(TENANT).orElseThrow();
        assertThat(report.vectorDocs()).isZero();
        assertThat(report.dimMismatches()).isEqualTo(2);
    }

    @Test
    void requiredModeFailsAPartlyVectorisedIndex() {
        corpus();
        var p = props(1024, 1024);
        var builder = new LuceneIndexBuilder(cache, p, new EmbeddingShape(p), true);

        assertThatThrownBy(() -> builder.build(TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 chunks without a vector");
    }
}
