package org.synanton.synquest.demo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synquest.engine.lucene.EmbeddingShape;
import org.synanton.synquest.service.KnowledgeSearchBackend;
import org.synanton.synquest.service.KnowledgeSearchBackend.KnowledgeCandidate;
import org.synanton.synquest.service.QueryEmbedder;
import org.slf4j.Logger;
import org.springframework.web.util.HtmlUtils;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Demo-only two-leg RRF. Query vectors come from the benchmark cache when the question is
 * known, otherwise they are embedded live through the GPU plane; chunk vectors always come
 * from the canonical ingestion cache ({@code embedding_content_cache}) so a newly uploaded
 * document is searchable on the dense leg without rebuilding a static file. Not a
 * production search path.
 */
public final class DemoSearchService {

    private static final Logger log = LoggerFactory.getLogger(DemoSearchService.class);
    public static final int K = 20;
    /** Upper bound on manifests scanned when assembling the canonical chunk-vector map. */
    private static final int MANIFEST_LIMIT = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Comparator<DemoResult> ORDER = Comparator
            .comparingDouble(DemoResult::finalScore).reversed()
            .thenComparingInt(r -> r.bm25Rank() == null ? Integer.MAX_VALUE : r.bm25Rank())
            .thenComparing(DemoResult::chunkId);

    private final KnowledgeSearchBackend backend;
    private final IngestionCacheClient cacheClient;
    private final QueryEmbedder queryEmbedder;
    private final EmbeddingShape shape;
    private final String embeddingModel;
    private final Path cachePath;
    private final Path queriesPath;
    private volatile Cache cache;

    public DemoSearchService(KnowledgeSearchBackend backend, IngestionCacheClient cacheClient,
                             QueryEmbedder queryEmbedder, EmbeddingShape shape, String embeddingModel,
                             Path cachePath, Path queriesPath) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.cacheClient = Objects.requireNonNull(cacheClient, "cacheClient");
        this.queryEmbedder = Objects.requireNonNull(queryEmbedder, "queryEmbedder");
        this.shape = Objects.requireNonNull(shape, "shape");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.cachePath = Objects.requireNonNull(cachePath, "cachePath");
        this.queriesPath = Objects.requireNonNull(queriesPath, "queriesPath");
    }

    public DemoResponse search(String tenantId, String query) throws Exception {
        String cacheQuery = HtmlUtils.htmlUnescape(query);
        Cache data = cache();
        float[] queryVector = data.queryVectors().get(cacheQuery);
        if (queryVector == null) {
            queryVector = embedQuery(tenantId, cacheQuery);
        }
        List<KnowledgeCandidate> bm25 = backend.candidates(tenantId, query, K);
        List<DenseHit> dense;
        if (queryVector == null) {
            log.info("No query embedding for '{}'; running BM25-only", cacheQuery);
            dense = List.of();
        } else {
            dense = dense(queryVector, canonicalChunkVectors(tenantId));
        }
        Map<String, Entry> entries = new HashMap<>();
        for (int i = 0; i < bm25.size(); i++) {
            KnowledgeCandidate c = bm25.get(i);
            String chunkId = c.objectId().replace(':', '#');
            entries.computeIfAbsent(chunkId, ignored -> new Entry(c, chunkId)).bm25Rank = i + 1;
        }
        for (int i = 0; i < dense.size(); i++) {
            String chunkId = dense.get(i).chunkId();
            Entry entry = entries.get(chunkId);
            if (entry == null) {
                entry = new Entry(null, chunkId);
                entries.put(chunkId, entry);
            }
            entry.denseRank = i + 1;
        }
        List<DemoResult> results = new ArrayList<>();
        for (Entry e : entries.values()) {
            double bm25Contribution = e.bm25Rank == null ? 0.0 : 1.0 / (K + e.bm25Rank);
            double denseContribution = e.denseRank == null ? 0.0 : 1.0 / (K + e.denseRank);
            DemoResult result = result(tenantId, e, bm25Contribution, denseContribution);
            results.add(result);
        }
        results.sort(ORDER);
        List<DemoResult> ranked = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            DemoResult r = results.get(i);
            ranked.add(new DemoResult(i + 1, r.finalScore(), r.bm25Rank(), r.bm25Contribution(),
                    r.denseRank(), r.denseContribution(), r.legsPresent(), r.chunkId(),
                    r.title(), r.snippet(), r.provenance()));
        }
        if (ranked.size() > K) {
            ranked = new ArrayList<>(ranked.subList(0, K));
        }
        return new DemoResponse(queryType(cacheQuery), ranked);
    }

    private DemoResult result(String tenantId, Entry e, double bm25Contribution, double denseContribution) {
        KnowledgeCandidate c = e.candidate;
        String title;
        String snippet;
        String sourceUri;
        Integer pageStart = null;
        Integer pageEnd = null;
        List<String> sectionPath = null;
        String provenanceRef;
        List<UUID> sourceVersions;
        if (c != null) {
            title = c.title();
            snippet = c.snippet();
            sourceUri = c.sourceUri();
            pageStart = c.pageStart();
            pageEnd = c.pageEnd();
            sectionPath = c.sectionPath();
            provenanceRef = c.provenanceRef();
            sourceVersions = c.sourceVersionIds() == null ? List.of() : List.copyOf(c.sourceVersionIds());
        } else {
            String[] parts = e.chunkId.split("#", 2);
            UUID contentRef = UUID.fromString(parts[0]);
            int ordinal = Integer.parseInt(parts[1]);
            ManifestRow manifest = cacheClient.readManifest(tenantId, contentRef).orElse(null);
            ChunkRow row = cacheClient.readChunks(tenantId, contentRef).stream()
                    .filter(r -> r.chunkOrdinal() == ordinal).findFirst().orElse(null);
            title = row != null && row.heading() != null && !row.heading().isBlank()
                    ? row.heading()
                    : manifest == null ? e.chunkId : manifest.sourceUri();
            snippet = row == null ? "" : row.chunkText();
            sourceUri = manifest == null ? null : manifest.sourceUri();
            if (row != null) {
                pageStart = row.pageStart() > 0 ? row.pageStart() : null;
                pageEnd = row.pageEnd() > 0 ? row.pageEnd() : null;
                sectionPath = row.sectionPath() == null || row.sectionPath().isBlank()
                        ? null : List.of(row.sectionPath().split("\\s*>\\s*"));
                sourceVersions = row.sourceVersionIds() == null ? List.of() : List.copyOf(row.sourceVersionIds());
            } else {
                sourceVersions = List.of();
            }
            provenanceRef = "/v1/knowledge/" + contentRef + ":" + ordinal + "/provenance";
        }
        List<String> legs = new ArrayList<>(2);
        if (e.bm25Rank != null) legs.add("bm25");
        if (e.denseRank != null) legs.add("dense");
        return new DemoResult(0, bm25Contribution + denseContribution, e.bm25Rank, bm25Contribution,
                e.denseRank, denseContribution, List.copyOf(legs), e.chunkId, title, snippet,
                new Provenance(sourceUri, sourceVersions, provenanceRef, pageStart, pageEnd, sectionPath));
    }

    private List<DenseHit> dense(float[] query, Map<String, float[]> chunks) {
        List<DenseHit> hits = new ArrayList<>(chunks.size());
        for (Map.Entry<String, float[]> e : chunks.entrySet()) {
            hits.add(new DenseHit(e.getKey(), cosine(query, e.getValue())));
        }
        hits.sort(Comparator.comparingDouble(DenseHit::score).reversed().thenComparing(DenseHit::chunkId));
        return hits.size() > K ? hits.subList(0, K) : hits;
    }

    /** Live query embedding via the GPU plane; a failure degrades to BM25-only, never 500. */
    private float[] embedQuery(String tenantId, String query) {
        try {
            return queryEmbedder.embed(query, tenantId);
        } catch (RuntimeException failure) {
            log.warn("Query embedding failed for '{}'; running BM25-only: {}", query, failure.getMessage());
            return null;
        }
    }

    /**
     * Canonical chunk vectors for the tenant, read from {@code embedding_content_cache}.
     * Only vectors produced by the configured model are considered, and they pass through
     * {@link EmbeddingShape#fit} so truncation/normalisation match the query vector.
     */
    private Map<String, float[]> canonicalChunkVectors(String tenantId) {
        Map<String, float[]> chunks = new HashMap<>();
        for (ManifestRow manifest : cacheClient.listManifest(tenantId, MANIFEST_LIMIT)) {
            UUID contentRef = manifest.contentRefId();
            for (EmbeddingRow row : cacheClient.listEmbeddings(tenantId, contentRef)) {
                if (!embeddingModel.equals(row.modelId())
                        || row.embedding() == null || row.embedding().length == 0) {
                    continue;
                }
                try {
                    chunks.put(contentRef + "#" + row.chunkOrdinal(), shape.fit(row.embedding()));
                } catch (IllegalStateException mismatch) {
                    log.warn("Skipping embedding for chunk {}#{} (model {}): {}",
                            contentRef, row.chunkOrdinal(), row.modelId(), mismatch.getMessage());
                }
            }
        }
        return chunks;
    }

    private Cache cache() throws IOException {
        Cache current = cache;
        if (current != null) return current;
        synchronized (this) {
            if (cache != null) return cache;
            JsonNode root = JSON.readTree(Files.readString(cachePath));
            Map<String, String> queryIds = new HashMap<>();
            for (String line : Files.readAllLines(queriesPath)) {
                if (line.isBlank()) continue;
                JsonNode q = JSON.readTree(line);
                queryIds.put(q.path("query_id").asText(), q.path("question").asText());
            }
            Map<String, float[]> queries = new HashMap<>();
            for (JsonNode q : root.path("queries")) {
                String question = queryIds.get(q.path("query_id").asText());
                if (question != null) queries.put(question, vector(q.path("embedding")));
            }
            cache = new Cache(Map.copyOf(queries));
            return cache;
        }
    }

    private static float[] vector(JsonNode node) {
        float[] vector = new float[node.size()];
        for (int i = 0; i < vector.length; i++) vector[i] = (float) node.get(i).asDouble();
        return vector;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) dot += a[i] * b[i];
        return dot;
    }

    static String queryType(String query) {
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        if (query.matches(".*(?:ERR_|SEC-|IATF\\s+\\d+|ISO\\s+\\d+|VLAN\\d+|v\\d+\\.\\d+|\\b\\d{4}\\b).*")) {
            return "exact";
        }
        if (lower.matches(".*(?:how does|why|define|explain|difference|compare|distinguish|meaning).*")) {
            return "conceptual";
        }
        return "mixed";
    }

    private record Cache(Map<String, float[]> queryVectors) {}
    private record DenseHit(String chunkId, double score) {}

    private static final class Entry {
        final KnowledgeCandidate candidate;
        final String chunkId;
        Integer bm25Rank;
        Integer denseRank;
        Entry(KnowledgeCandidate candidate, String chunkId) {
            this.candidate = candidate;
            this.chunkId = chunkId;
        }
    }

    public record DemoResponse(@JsonProperty("query_type") String queryType,
                               @JsonProperty("results") List<DemoResult> results) {}

    public record DemoResult(
            @JsonProperty("final_rank") int finalRank,
            @JsonProperty("final_score") double finalScore,
            @JsonProperty("bm25_rank") @JsonInclude(JsonInclude.Include.ALWAYS) Integer bm25Rank,
            @JsonProperty("bm25_contribution") double bm25Contribution,
            @JsonProperty("dense_rank") @JsonInclude(JsonInclude.Include.ALWAYS) Integer denseRank,
            @JsonProperty("dense_contribution") double denseContribution,
            @JsonProperty("legs_present") List<String> legsPresent,
            @JsonProperty("chunk_id") String chunkId,
            @JsonProperty("title") String title,
            @JsonProperty("snippet") String snippet,
            @JsonProperty("provenance") Provenance provenance) {}

    public record Provenance(
            @JsonProperty("source_uri") String sourceUri,
            @JsonProperty("source_version_ids") List<UUID> sourceVersionIds,
            @JsonProperty("provenance_ref") String provenanceRef,
            @JsonProperty("page_start") @JsonInclude(JsonInclude.Include.ALWAYS) Integer pageStart,
            @JsonProperty("page_end") @JsonInclude(JsonInclude.Include.ALWAYS) Integer pageEnd,
            @JsonProperty("section_path") @JsonInclude(JsonInclude.Include.ALWAYS) List<String> sectionPath) {}
}
