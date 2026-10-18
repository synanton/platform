package org.synanton.synquest.service;

import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.synquest.api.dto.SearchRequest;
import org.synanton.synquest.api.dto.SearchResponse;

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
public class LuceneKnowledgeSearchBackend implements KnowledgeSearchBackend {

    public static final String STRATEGY = "lexical";

    private final LuceneIndexBuilder indexBuilder;
    private final SearchService searchService;
    private final IngestionCacheClient cacheClient;

    public LuceneKnowledgeSearchBackend(
            LuceneIndexBuilder indexBuilder, SearchService searchService, IngestionCacheClient cacheClient) {
        this.indexBuilder = indexBuilder;
        this.searchService = searchService;
        this.cacheClient = cacheClient;
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
    public int project(String tenantId) throws Exception {
        indexBuilder.build(tenantId);
        searchService.initTenant(tenantId);
        return indexBuilder.lastReport(tenantId)
                .map(LuceneIndexBuilder.BuildReport::docs)
                .orElse(0);
    }

    @Override
    public List<KnowledgeCandidate> candidates(String tenantId, String query, int limit) throws Exception {
        // Lexical-only candidate generation: BM25 over the tenant's projection, no
        // dense leg and no embedding dependency — the CPU slice's retrieval path.
        SearchRequest lexicalOnly = new SearchRequest(tenantId, query, limit, 0, limit, null);
        SearchResponse response = searchService.search(lexicalOnly);

        Map<UUID, Map<Integer, Set<UUID>>> versionsByChunk = new HashMap<>();
        for (var hit : response.hits()) {
            var rows = cacheClient.readChunks(tenantId, hit.contentRefId());
            for (ChunkRow row : rows) {
                versionsByChunk.computeIfAbsent(hit.contentRefId(), k -> new HashMap<>())
                        .computeIfAbsent(row.chunkOrdinal(), k -> new java.util.HashSet<>())
                        .addAll(row.sourceVersionIds());
            }
        }
        return response.hits().stream()
                .map(hit -> {
                    Set<UUID> versions = versionsByChunk.getOrDefault(hit.contentRefId(), Map.of())
                            .getOrDefault(hit.chunkOrdinal(), Set.of());
                    String title = hit.heading() == null || hit.heading().isBlank()
                            ? hit.sourceUri() : hit.heading();
                    return new KnowledgeCandidate(
                            hit.chunkId(), hit.contentRefId(), hit.chunkOrdinal(), hit.score(),
                            title, hit.snippet(), hit.sourceUri(), Set.copyOf(versions));
                })
                .toList();
    }
}
