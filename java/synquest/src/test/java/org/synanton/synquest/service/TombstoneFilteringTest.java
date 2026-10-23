package org.synanton.synquest.service;

import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.synquest.api.dto.Hit;
import org.synanton.synquest.api.dto.SearchRequest;
import org.synanton.synquest.api.dto.SearchResponse;
import org.synanton.synquest.config.SynquestProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TombstoneFilteringTest {

    private static final String QUERY = "retention policy";
    private static final int LIMIT = 2;
    private static final int MARGIN = 2;

    @Test
    void tombstoneDominatedWindowRefetchesAndReusesOneSnapshot() throws Exception {
        String tenant = "tenant-tombstone-refetch";
        List<Hit> hits = new ArrayList<>();
        Map<UUID, UUID> versionsByRef = new HashMap<>();
        Set<UUID> tombstoned = new java.util.HashSet<>();
        for (int i = 0; i < 4; i++) {
            UUID ref = UUID.randomUUID();
            UUID version = UUID.randomUUID();
            hits.add(hit(ref, i));
            versionsByRef.put(ref, version);
            tombstoned.add(version);
        }
        List<Hit> liveHits = new ArrayList<>();
        for (int i = 4; i < 6; i++) {
            UUID ref = UUID.randomUUID();
            UUID version = UUID.randomUUID();
            liveHits.add(hit(ref, i));
            versionsByRef.put(ref, version);
        }

        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        SearchService search = mock(SearchService.class);
        when(cache.readTombstonedVersionIds(tenant)).thenReturn(tombstoned);
        when(cache.readChunks(eq(tenant), any(UUID.class))).thenAnswer(invocation -> {
            UUID ref = invocation.getArgument(1);
            return List.of(chunk(tenant, ref, versionsByRef.get(ref)));
        });
        List<Hit> all = new ArrayList<>(hits);
        all.addAll(liveHits);
        when(search.search(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            int fetch = request.topKLexical();
            return new SearchResponse(all.subList(0, Math.min(fetch, all.size())), null, null);
        });

        LuceneKnowledgeSearchBackend backend = backend(cache, search, MARGIN);
        var candidates = backend.candidates(tenant, QUERY, LIMIT);

        assertThat(candidates).hasSize(LIMIT);
        assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.sourceVersionIds()).hasSize(1));
        verify(search, times(2)).search(any(SearchRequest.class));
        verify(cache, times(1)).readTombstonedVersionIds(tenant);
    }

    @Test
    void cleanWindowDoesNotTriggerARefetch() throws Exception {
        String tenant = "tenant-tombstone-clean";
        List<Hit> hits = new ArrayList<>();
        Map<UUID, UUID> versionsByRef = new HashMap<>();
        for (int i = 0; i < LIMIT; i++) {
            UUID ref = UUID.randomUUID();
            UUID version = UUID.randomUUID();
            hits.add(hit(ref, i));
            versionsByRef.put(ref, version);
        }

        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        SearchService search = mock(SearchService.class);
        when(cache.readTombstonedVersionIds(tenant)).thenReturn(Set.of());
        when(cache.readChunks(eq(tenant), any(UUID.class))).thenAnswer(invocation -> {
            UUID ref = invocation.getArgument(1);
            return List.of(chunk(tenant, ref, versionsByRef.get(ref)));
        });
        when(search.search(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            int fetch = request.topKLexical();
            return new SearchResponse(hits.subList(0, Math.min(fetch, hits.size())), null, null);
        });

        LuceneKnowledgeSearchBackend backend = backend(cache, search, MARGIN);
        var candidates = backend.candidates(tenant, QUERY, LIMIT);

        assertThat(candidates).hasSize(LIMIT);
        verify(search, times(1)).search(any(SearchRequest.class));
        verify(cache, times(1)).readTombstonedVersionIds(tenant);
    }

    @Test
    void sharedChunkKeepsTheLiveSiblingAndHidesTheDeletedVersion() throws Exception {
        String tenant = "tenant-tombstone-shared";
        UUID ref = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        SearchService search = mock(SearchService.class);
        when(cache.readTombstonedVersionIds(tenant)).thenReturn(Set.of(deleted));
        when(cache.readChunks(eq(tenant), eq(ref))).thenReturn(List.of(
            chunk(tenant, ref, deleted, live)));
        when(search.search(any(SearchRequest.class))).thenReturn(
            new SearchResponse(List.of(hit(ref, 0)), null, null));

        var candidates = backend(cache, search, MARGIN).candidates(tenant, QUERY, LIMIT);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().sourceVersionIds()).containsExactly(live);
    }

    @Test
    void tombstonedOnlyChunkDisappears() throws Exception {
        String tenant = "tenant-tombstone-only";
        UUID ref = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        SearchService search = mock(SearchService.class);
        when(cache.readTombstonedVersionIds(tenant)).thenReturn(Set.of(deleted));
        when(cache.readChunks(eq(tenant), eq(ref))).thenReturn(List.of(
            chunk(tenant, ref, deleted)));
        when(search.search(any(SearchRequest.class))).thenReturn(
            new SearchResponse(List.of(hit(ref, 0)), null, null));

        var candidates = backend(cache, search, MARGIN).candidates(tenant, QUERY, LIMIT);

        assertThat(candidates).isEmpty();
    }

    private static LuceneKnowledgeSearchBackend backend(
            IngestionCacheClient cache, SearchService search, int margin) {
        SynquestProperties props = new SynquestProperties(
            new SynquestProperties.Index("/tmp/synquest-tombstone-test", false, 30),
            new SynquestProperties.Search(20, 100, 100, 60, "COSINE", 1.0, 200, margin),
            null, null);
        return new LuceneKnowledgeSearchBackend(mock(LuceneIndexBuilder.class), search, cache, props);
    }

    private static Hit hit(UUID contentRefId, int ordinal) {
        return new Hit(contentRefId, 0, 1.0, 0, 1.0, 0, ordinal + 1,
            "snippet", "upload://source/" + contentRefId + ".pdf", 1, 1, "Section",
            "Section", List.of(), 10, "", false, "");
    }

    private static ChunkRow chunk(String tenant, UUID contentRef, UUID version) {
        return chunk(tenant, contentRef, version, null);
    }

    private static ChunkRow chunk(String tenant, UUID contentRef, UUID version, UUID secondVersion) {
        Set<UUID> versions = secondVersion == null ? Set.of(version) : Set.of(version, secondVersion);
        return new ChunkRow(tenant, contentRef, 0, "text", "sha", 1, 1, "Section",
            "SECTION", "Section", "[]", 10, "", false, ChunkRow.PUBLIC_ONLY, "", "", 0,
            versions, null);
    }
}
