package org.synanton.synquest.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.synanton.synquest.engine.lucene.LuceneIndexBuilder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.synquest.api.dto.SearchRequest;
import org.synanton.synquest.api.dto.SearchResponse;
import org.synanton.synquest.config.SynquestProperties;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final Logger log = LoggerFactory.getLogger(LuceneKnowledgeSearchBackend.class);

    public static final String STRATEGY = "bm25";

    private static final int MAX_RETRIEVAL_WINDOW = 1000;

    private final LuceneIndexBuilder indexBuilder;
    private final SearchService searchService;
    private final IngestionCacheClient cacheClient;
    private final int tombstoneOverfetchMargin;
    private final boolean failOpenUnmapped;
    private final MeterRegistry registry;

    @Autowired
    public LuceneKnowledgeSearchBackend(
            LuceneIndexBuilder indexBuilder, SearchService searchService, IngestionCacheClient cacheClient,
            SynquestProperties properties,
            @Value("${synquest.search.visibility.fail-open-unmapped:true}") boolean failOpenUnmapped,
            MeterRegistry registry) {
        this.indexBuilder = indexBuilder;
        this.searchService = searchService;
        this.cacheClient = cacheClient;
        this.tombstoneOverfetchMargin = Math.max(0, properties.search().tombstoneOverfetchMargin());
        this.failOpenUnmapped = failOpenUnmapped;
        this.registry = registry;
    }

    public LuceneKnowledgeSearchBackend(
            LuceneIndexBuilder indexBuilder, SearchService searchService, IngestionCacheClient cacheClient,
            SynquestProperties properties) {
        this(indexBuilder, searchService, cacheClient, properties, true, new SimpleMeterRegistry());
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
    public boolean hasCanonicalData(String tenantId) {
        return !cacheClient.listManifest(tenantId, 1).isEmpty();
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

        // Query-time visibility (#656/#694): the index is an immutable projection.
        // A chunk is visible only while at least one of its supporting source
        // versions is the authoritative current revision. This is orthogonal to the
        // #654 ownership protocol: it reads the current pointer and takes no locks.
        Map<UUID, SourceVersionRow> versionsById = tenantVersions(tenantId);
        Map<String, Optional<UUID>> currentRevisionBySource = new HashMap<>();

        int fetch = Math.min(limit + tombstoneOverfetchMargin, MAX_RETRIEVAL_WINDOW);
        while (true) {
            SearchResponse response = searchService.search(new SearchRequest(tenantId, query, fetch, 0, fetch, null));
            List<KnowledgeCandidate> live = liveCandidates(
                    tenantId, response.hits(), tombstoned, versionsById, currentRevisionBySource, limit);
            if (live.size() >= limit || response.hits().size() < fetch || fetch >= MAX_RETRIEVAL_WINDOW) {
                return live;
            }
            int nextFetch = Math.min(fetch + tombstoneOverfetchMargin, MAX_RETRIEVAL_WINDOW);
            log.info("Tombstone filtering refetch for tenant '{}': window {} -> {} for query '{}'",
                tenantId, fetch, nextFetch, query);
            fetch = nextFetch;
        }
    }

    private static List<String> sectionPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(value.split("\\s*>\\s*"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private List<KnowledgeCandidate> liveCandidates(
            String tenantId, List<org.synanton.synquest.api.dto.Hit> hits, Set<UUID> tombstoned,
            Map<UUID, SourceVersionRow> versionsById,
            Map<String, Optional<UUID>> currentRevisionBySource, int limit) {
        Map<UUID, List<ChunkRow>> chunksByRef = new HashMap<>();
        List<KnowledgeCandidate> candidates = new java.util.ArrayList<>();
        for (var hit : hits) {
            List<ChunkRow> rows = chunksByRef.computeIfAbsent(
                hit.contentRefId(), ref -> cacheClient.readChunks(tenantId, ref));
            Set<UUID> liveVersions = new java.util.HashSet<>();
            ChunkRow matched = null;
            String hiddenReason = null;
            for (ChunkRow row : rows) {
                if (row.chunkOrdinal() != hit.chunkOrdinal()) {
                    continue;
                }
                matched = row;
                if (row.sourceVersionIds() == null || row.sourceVersionIds().isEmpty()) {
                    if (!failOpenUnmapped) {
                        hiddenReason = hiddenReason == null ? "unmapped" : hiddenReason;
                    }
                    continue;
                }
                for (UUID versionId : row.sourceVersionIds()) {
                    if (tombstoned.contains(versionId)) {
                        continue;
                    }
                    Visibility visibility = visibility(
                            tenantId, versionId, versionsById, currentRevisionBySource);
                    if (visibility == Visibility.CURRENT) {
                        liveVersions.add(versionId);
                    } else if (visibility == Visibility.UNMAPPED
                            || visibility == Visibility.NO_CURRENT) {
                        if (failOpenUnmapped) {
                            // Pre-#654 compatibility: unmapped/no-pointer versions remain
                            // visible until the #699 backfill + fail-closed switch.
                            liveVersions.add(versionId);
                        } else {
                            hiddenReason = hiddenReason == null ? visibility.reason : hiddenReason;
                        }
                    }
                }
            }
            if (liveVersions.isEmpty()) {
                if (hiddenReason != null) {
                    registry.counter("search.visibility.hidden_unmapped", "reason", hiddenReason).increment();
                }
                continue;
            }
            if (matched == null) {
                continue;
            }
            String title = hit.heading() == null || hit.heading().isBlank()
                ? hit.sourceUri() : hit.heading();
            candidates.add(new KnowledgeCandidate(
                hit.chunkId(), hit.contentRefId(), hit.chunkOrdinal(), hit.score(),
                title, hit.snippet(), hit.sourceUri(), Set.copyOf(liveVersions),
                matched.pageStart() > 0 ? matched.pageStart() : null,
                matched.pageEnd() > 0 ? matched.pageEnd() : null,
                matched.sectionPath() == null || matched.sectionPath().isBlank() ? null : matched.sectionPath(),
                sectionPath(matched.sectionPath())));
            if (candidates.size() >= limit) {
                break;
            }
        }
        return candidates;
    }

    /**
     * Per-query version index for visibility. The bounded tenant scan is the same
     * source/identity/version path used by the existing read surfaces; it never
     * crosses tenant boundaries.
     */
    private Map<UUID, SourceVersionRow> tenantVersions(String tenantId) {
        Map<UUID, SourceVersionRow> versions = new HashMap<>();
        List<SourceRow> sources = cacheClient.listSources(tenantId);
        if (sources == null) {
            return versions;
        }
        for (SourceRow source : sources) {
            List<SourceIdentityRow> identities = cacheClient.listSourceIdentities(
                    tenantId, source.sourceId(), source.connectorId());
            if (identities == null) {
                continue;
            }
            for (SourceIdentityRow identity : identities) {
                List<SourceVersionRow> rows = cacheClient.listSourceVersions(
                        tenantId, identity.connectorId(), identity.sourceId(), identity.resourceId());
                if (rows == null) {
                    continue;
                }
                for (SourceVersionRow row : rows) {
                    versions.put(row.versionId(), row);
                }
            }
        }
        return versions;
    }

    private enum Visibility {
        CURRENT(null),
        SUPERSEDED(null),
        UNMAPPED("unmapped"),
        NO_CURRENT("no_current");

        private final String reason;

        Visibility(String reason) {
            this.reason = reason;
        }
    }

    /**
     * A supporting version is visible while it is the authoritative current
     * revision for its source. An unmapped version and a mapped source with no
     * current pointer are the #699 fail-open cases; the flag decides whether they
     * remain visible or are hidden and counted.
     */
    private Visibility visibility(String tenantId, UUID versionId,
                                  Map<UUID, SourceVersionRow> versionsById,
                                  Map<String, Optional<UUID>> currentRevisionBySource) {
        SourceVersionRow version = versionsById.get(versionId);
        if (version == null) {
            return Visibility.UNMAPPED;
        }
        Optional<UUID> current = currentRevisionBySource.computeIfAbsent(
                version.sourceId(), sourceId -> cacheClient.readCurrentRevisionId(tenantId, sourceId));
        if (current.isEmpty()) {
            return Visibility.NO_CURRENT;
        }
        return current.get().equals(versionId) ? Visibility.CURRENT : Visibility.SUPERSEDED;
    }
}
