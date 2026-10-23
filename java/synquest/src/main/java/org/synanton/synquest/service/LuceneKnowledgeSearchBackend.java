package org.synanton.synquest.service;

import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.synquest.api.dto.SearchRequest;
import org.synanton.synquest.api.dto.SearchResponse;
import org.synanton.synquest.config.SynquestProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lucene adapter for the #255 search projection and query path: projects
 * canonical knowledge (manifest + chunks) into a per-tenant Lucene index and
 * generates tenant-scoped BM25 candidates. The public query contract never sees
 * Lucene types — candidates carry stable canonical references only.
 */
@Component
public class LuceneKnowledgeSearchBackend implements KnowledgeSearchBackend {

    public static final String STRATEGY = "bm25";

    private static final int MAX_RETRIEVAL_WINDOW = 1000;

    private final LuceneIndexBuilder indexBuilder;
    private final SearchService searchService;
    private final IngestionCacheClient cacheClient;
    private final int tombstoneOverfetchMargin;

    public LuceneKnowledgeSearchBackend(
            LuceneIndexBuilder indexBuilder, SearchService searchService, IngestionCacheClient cacheClient,
            SynquestProperties properties) {
        this.indexBuilder = indexBuilder;
        this.searchService = searchService;
        this.cacheClient = cacheClient;
        this.tombstoneOverfetchMargin = Math.max(0, properties.search().tombstoneOverfetchMargin());
    }

    @Override
    public String strategy() {
        return STRATEGY;
    }

    @Override
    public boolean isReady(String tenantId) {
        return searchService.isReady(tenantId);
    }

    @Override
    public long generation(String tenantId) {
        try {
            return searchService.stats(tenantId).indexGeneration();
        } catch (java.io.IOException unreadable) {
            return -1;
        }
    }

    @Override
    public int project(String tenantId) throws Exception {
        indexBuilder.build(tenantId);
        searchService.initTenant(tenantId);
        return indexBuilder.lastReport(tenantId)
                .map(LuceneIndexBuilder.BuildReport::docs)
                .orElse(0);
    }

    @Override
    public List<KnowledgeCandidate> candidates(String tenantId, String query, int limit) throws Exception {
        // One immutable snapshot per query. The initial fetch and any guard refetch use it;
        // a document deleted after this point may still appear in this query's result.
        Set<UUID> tombstoned = cacheClient.readTombstonedVersionIds(tenantId);
        if (tombstoned == null) {
            tombstoned = Set.of();
        }

        int fetch = Math.min(limit + tombstoneOverfetchMargin, MAX_RETRIEVAL_WINDOW);
        while (true) {
            SearchResponse response = searchService.search(new SearchRequest(tenantId, query, fetch, 0, fetch, null));
            List<KnowledgeCandidate> live = liveCandidates(tenantId, response.hits(), tombstoned, limit);
            if (live.size() >= limit || response.hits().size() < fetch || fetch >= MAX_RETRIEVAL_WINDOW) {
                return live;
            }
            fetch = Math.min(fetch + tombstoneOverfetchMargin, MAX_RETRIEVAL_WINDOW);
        }
    }

    private List<KnowledgeCandidate> liveCandidates(
            String tenantId, List<org.synanton.synquest.api.dto.Hit> hits, Set<UUID> tombstoned, int limit) {
        Map<UUID, List<ChunkRow>> chunksByRef = new HashMap<>();
        List<KnowledgeCandidate> candidates = new java.util.ArrayList<>();
        for (var hit : hits) {
            List<ChunkRow> rows = chunksByRef.computeIfAbsent(
                hit.contentRefId(), ref -> cacheClient.readChunks(tenantId, ref));
            Set<UUID> liveVersions = new java.util.HashSet<>();
            for (ChunkRow row : rows) {
                if (row.chunkOrdinal() != hit.chunkOrdinal() || row.sourceVersionIds() == null) {
                    continue;
                }
                for (UUID versionId : row.sourceVersionIds()) {
                    if (!tombstoned.contains(versionId)) {
                        liveVersions.add(versionId);
                    }
                }
            }
            if (liveVersions.isEmpty()) {
                continue;
            }
            String title = hit.heading() == null || hit.heading().isBlank()
                ? hit.sourceUri() : hit.heading();
            candidates.add(new KnowledgeCandidate(
                hit.chunkId(), hit.contentRefId(), hit.chunkOrdinal(), hit.score(),
                title, hit.snippet(), hit.sourceUri(), Set.copyOf(liveVersions)));
            if (candidates.size() >= limit) {
                break;
            }
        }
        return candidates;
    }
}
