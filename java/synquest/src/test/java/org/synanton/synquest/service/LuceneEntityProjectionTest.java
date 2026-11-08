package org.synanton.synquest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synquest.config.SynquestProperties;
import org.synanton.synquest.engine.lucene.LuceneIndexBuilder;
import org.synanton.synquest.engine.lucene.LuceneSearchEngine;
import org.synanton.synquest.search.EmbeddedLuceneAdapter;
import org.synanton.synquest.search.EntitySearchDocument;
import org.synanton.synquest.search.SearchDocumentType;
import org.synanton.synquest.search.SearchFilter;
import org.synanton.synquest.search.SearchQuery;
import org.synanton.synquest.search.SearchResult;

class LuceneEntityProjectionTest {

    private static final String TENANT = "tenant-entity-projection";

    @TempDir Path indexRoot;

    @Test
    void entityAndChunkSearchesShareTheIndexButNeverCross() throws Exception {
        UUID ref = UUID.randomUUID();
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        CacheCursorStubs.wire(cache);
        when(cache.listManifest(eq(TENANT), anyInt())).thenReturn(List.of(manifest(ref)));
        when(cache.readChunks(TENANT, ref)).thenReturn(List.of(
                new ChunkRow(TENANT, ref, 0, "alpha chunk body", "sha0")));
        when(cache.readEmbedding(eq(TENANT), any(), anyInt(), anyString())).thenReturn(Optional.empty());

        SynquestProperties props = new SynquestProperties(
                new SynquestProperties.Index(indexRoot.toString(), false, 30), null,
                new SynquestProperties.Embedding("test-model", 4, true, 0), null);
        LuceneIndexBuilder builder = new LuceneIndexBuilder(cache, props);
        QueryEmbedder embedder = mock(QueryEmbedder.class);
        when(embedder.required()).thenReturn(false);
        LuceneSearchEngine engine = new LuceneSearchEngine(new EmbeddedLuceneAdapter(builder), embedder, props);
        builder.build(TENANT);
        engine.initTenant(TENANT);
        when(embedder.embedForSearch("Halcyon", TENANT)).thenReturn(
                new QueryEmbedder.QueryVector(new float[]{1, 0, 0, 0}, false));
        engine.upsertEntities(TENANT, List.of(new EntitySearchDocument(
                "e1", "Halcyon-7", List.of("Halcyon"), "MaintenanceSystem",
                "auxiliary coolant loop", new float[]{1, 0, 0, 0})));

        SearchQuery entityQuery = new SearchQuery("Halcyon", 10, 0, 10, 60,
                false, null, null, null, null);
        SearchResult entityResult = engine.search(entityQuery,
                new SearchFilter(TENANT, SearchDocumentType.ENTITY));
        assertThat(entityResult.documents()).isNotEmpty();
        assertThat(entityResult.documents()).allSatisfy(document -> {
            assertThat(document.documentType()).isEqualTo(SearchDocumentType.ENTITY);
            assertThat(document.contentRefId()).isNull();
        });
        assertThat(entityResult.documents()).anyMatch(document -> "e1".equals(document.entityId()));

        SearchQuery denseEntityQuery = new SearchQuery("Halcyon", 10, 4, 10, 60,
                false, null, null, null, null);
        assertThat(engine.search(denseEntityQuery, new SearchFilter(TENANT, SearchDocumentType.ENTITY))
                .documents()).anyMatch(document -> "e1".equals(document.entityId()));

        SearchQuery chunkQuery = new SearchQuery("alpha chunk", 10, 0, 10, 60,
                false, null, null, null, null);
        SearchResult chunkResult = engine.search(chunkQuery,
                new SearchFilter(TENANT, SearchDocumentType.CHUNK));
        assertThat(chunkResult.documents()).isNotEmpty();
        assertThat(chunkResult.documents()).allSatisfy(document -> {
            assertThat(document.documentType()).isEqualTo(SearchDocumentType.CHUNK);
            assertThat(document.entityId()).isNull();
            assertThat(document.chunkOrdinal()).isZero();
        });

        assertThat(engine.search(entityQuery, new SearchFilter(TENANT, SearchDocumentType.CHUNK))
                .documents()).isEmpty();
        assertThat(engine.search(chunkQuery, new SearchFilter(TENANT, SearchDocumentType.ENTITY))
                .documents()).isEmpty();

        // A full chunk rebuild must not wipe the shared ENTITY projection.
        engine.reindex(TENANT);
        assertThat(engine.search(entityQuery, new SearchFilter(TENANT, SearchDocumentType.ENTITY))
                .documents()).anyMatch(document -> "e1".equals(document.entityId()));
        assertThat(engine.search(chunkQuery, new SearchFilter(TENANT, SearchDocumentType.CHUNK))
                .documents()).isNotEmpty();
    }

    private static ManifestRow manifest(UUID ref) {
        return new ManifestRow(TENANT, ref, Instant.now(), 1, "fixed-window", 1, "CHUNKED",
                "CONTENT_CACHE", "", "file:///" + ref, "sha", 10, "text/plain", "FULL",
                null, "", null);
    }
}
