package org.synanton.synquest.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.TopDocs;
import org.synanton.synquest.api.dto.*;
import org.synanton.synquest.config.SynquestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Status { STARTING, READY, ERROR }

    private final LuceneIndexBuilder indexBuilder;
    private final QueryEmbedder queryEmbedder;
    private final SynquestProperties props;

    private final Map<String, AtomicReference<HybridSearcher>> searcherRefs = new ConcurrentHashMap<>();
    private final Map<String, Object> rebuildLocks = new ConcurrentHashMap<>();

    private volatile Status status = Status.STARTING;

    private final ExecutorService searchPool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()));

    private final SearchReranker reranker;
    private final org.synanton.synquest.config.SynquestRerankProperties rerankProps;

    public SearchService(LuceneIndexBuilder indexBuilder,
                         QueryEmbedder queryEmbedder,
                         SynquestProperties props) {
        this(indexBuilder, queryEmbedder, props, (SearchReranker) null, new org.synanton.synquest.config.SynquestRerankProperties());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SearchService(LuceneIndexBuilder indexBuilder,
                         QueryEmbedder queryEmbedder,
                         SynquestProperties props,
                         org.springframework.beans.factory.ObjectProvider<SearchReranker> reranker,
                         org.synanton.synquest.config.SynquestRerankProperties rerankProps) {
        this(indexBuilder, queryEmbedder, props, reranker.getIfAvailable(), rerankProps);
    }

    public SearchService(LuceneIndexBuilder indexBuilder,
                         QueryEmbedder queryEmbedder,
                         SynquestProperties props,
                         SearchReranker reranker,
                         org.synanton.synquest.config.SynquestRerankProperties rerankProps) {
        this.indexBuilder = indexBuilder;
        this.queryEmbedder = queryEmbedder;
        this.props = props;
        this.reranker = reranker;
        this.rerankProps = rerankProps;
    }

    public void initTenant(String tenant) {
        try {
            if (props.index().rebuildOnBootIfEmpty() && indexBuilder.isEmpty(tenant)) {
                log.info("Index is empty for tenant '{}', building now...", tenant);
                indexBuilder.build(tenant);
            }
            Path idxPath = indexBuilder.indexPath(tenant);
            HybridSearcher searcher = new HybridSearcher(idxPath, props.embedding().dim());
            searcherRefs.computeIfAbsent(tenant, t -> new AtomicReference<>()).set(searcher);
            rebuildLocks.put(tenant, new Object());
            status = Status.READY;
            log.info("Synquest ready for tenant '{}'", tenant);
        } catch (Exception e) {
            status = Status.ERROR;
            log.error("Failed to initialise index for tenant '{}'", tenant, e);
        }
    }

    public SearchResponse search(SearchRequest req) throws IOException {
        String tenant = req.tenant() != null ? req.tenant() : "demo";
        HybridSearcher searcher = getSearcher(tenant);
        if (searcher == null) {
            throw new IllegalStateException("Index not ready for tenant: " + tenant);
        }

        int topK = req.topK() != null ? req.topK() : props.search().defaultTopK();
        int topKDense = req.topKDense() != null ? req.topKDense() : props.search().defaultTopKDense();
        int topKLexical = req.topKLexical() != null ? req.topKLexical() : props.search().defaultTopKLexical();
        int rrfK = req.rrfK() != null ? req.rrfK() : props.search().defaultRrfK();

        long t0 = System.currentTimeMillis();
        long queryInputChars = req.query() == null ? 0 : req.query().length();

        float[] queryVec = null;
        long embedMs = 0;
        boolean embedSkipped = false;
        boolean embedCached = false;
        // top_k_dense <= 0 means "no dense side" (BM25-only rows such as T01): don't embed the query
        // and don't run KNN. Lucene rejects k=0, which under synquest.embedding.required would have
        // turned an intentionally lexical search into a 503.
        boolean denseRequested = topKDense > 0;
        if (denseRequested) try {
            long embedStart = System.currentTimeMillis();
            QueryEmbedder.QueryVector qv = queryEmbedder.embedForSearch(req.query(), tenant);
            queryVec = qv.vector();
            embedCached = qv.cached();
            embedMs = System.currentTimeMillis() - embedStart;
        } catch (Exception e) {
            if (queryEmbedder.required()) {
                throw new EmbeddingUnavailableException(e);
            }
            embedSkipped = true;
            log.warn("Query embedding unavailable, using BM25 only: {}", e.getMessage());
        }

        final float[] denseVec = queryVec;
        Future<TopDocs> denseFuture = searchPool.submit(() -> {
            if (!denseRequested || denseVec == null) {
                return new TopDocs(new org.apache.lucene.search.TotalHits(0,
                        org.apache.lucene.search.TotalHits.Relation.EQUAL_TO), new org.apache.lucene.search.ScoreDoc[0]);
            }
            try {
                return searcher.dense(denseVec, topKDense);
            } catch (Exception e) {
                if (queryEmbedder.required()) {
                    // e.g. an index built at another dimension: never degrade silently to BM25-only
                    throw new EmbeddingUnavailableException(e);
                }
                log.warn("Dense search skipped: {}", e.getMessage());
                return new TopDocs(new org.apache.lucene.search.TotalHits(0,
                        org.apache.lucene.search.TotalHits.Relation.EQUAL_TO), new org.apache.lucene.search.ScoreDoc[0]);
            }
        });
        Future<TopDocs> lexicalFuture = searchPool.submit(() -> searcher.lexical(req.query(), topKLexical));

        long denseStart = System.currentTimeMillis();
        TopDocs denseResults;
        TopDocs lexicalResults;
        try {
            denseResults = denseFuture.get();
            long denseMs = System.currentTimeMillis() - denseStart;

            long lexicalStart = System.currentTimeMillis();
            lexicalResults = lexicalFuture.get();
            long lexicalMs = System.currentTimeMillis() - lexicalStart;

            // Rerank (B2/T10): fuse a wider candidate list, score full chunk text with the
            // cross-encoder, reorder, cut to top_k. Fail closed: never return RRF order as "reranked".
            boolean rerankRequested = Boolean.TRUE.equals(req.rerank());
            int candidates = topK;
            if (rerankRequested) {
                if (reranker == null) {
                    throw new RerankUnavailableException(
                            "rerank requested but synquest.rerank is not enabled (gpu-plane profile)", null);
                }
                int asked = req.rerankCandidates() != null ? req.rerankCandidates() : rerankProps.getDefaultCandidates();
                candidates = Math.max(topK, Math.min(asked, rerankProps.getMaxCandidates()));
            }

            long fusionStart = System.currentTimeMillis();
            List<RrfFusion.FusedHit> fused = RrfFusion.combine(denseResults, lexicalResults, candidates, rrfK);
            long fusionMs = System.currentTimeMillis() - fusionStart;

            var stored = searcher.storedFields();
            List<Hit> hits = new ArrayList<>(fused.size());
            List<String> texts = new ArrayList<>(fused.size());
            for (RrfFusion.FusedHit fh : fused) {
                Document doc = stored.document(fh.docId());
                String contentRefId = doc.get("content_ref_id");
                int chunkOrdinal = Integer.parseInt(Objects.requireNonNullElse(doc.get("chunk_ordinal"), "0"));
                String text = doc.get("text");
                texts.add(text == null ? "" : text);
                String snippet = text != null && text.length() > 200 ? text.substring(0, 200) + "…" : text;
                hits.add(new Hit(
                        UUID.fromString(contentRefId),
                        chunkOrdinal,
                        fh.rrfScore(),
                        fh.denseScore(),
                        fh.lexicalScore(),
                        fh.rankDense(),
                        fh.rankLexical(),
                        snippet,
                        doc.get("source_uri"),
                        parseInt(doc.get("page_start"), -1),
                        parseInt(doc.get("page_end"), -1),
                        doc.get("section_path"),
                        doc.get("heading"),
                        parseSourceElements(doc.get("source_elements")),
                        parseInt(doc.get("token_count"), 0),
                        emptyToNull(doc.get("structured_content")),
                        parseBoolean(doc.get("is_partial_section")),
                        emptyToNull(doc.get("ingest_usage"))));
            }

            Long rerankMs = null;
            if (rerankRequested && !hits.isEmpty()) {
                long rerankStart = System.currentTimeMillis();
                int maxChars = rerankProps.getMaxPassageChars();
                List<String> passages = texts.stream()
                        .map(t -> t.length() > maxChars ? t.substring(0, maxChars) : t).toList();
                double[] scores;
                try {
                    scores = reranker.scores(tenant, req.query(), passages);
                } catch (RuntimeException e) {
                    throw new RerankUnavailableException("rerank failed: " + e.getMessage(), e);
                }
                List<Hit> reranked = new ArrayList<>(hits.size());
                for (int i = 0; i < hits.size(); i++) {
                    reranked.add(hits.get(i).withScoreRerank(scores[i]));
                }
                // stable sort: equal rerank scores keep RRF order
                reranked.sort(java.util.Comparator.comparingDouble((Hit h) -> h.scoreRerank()).reversed());
                hits = new ArrayList<>(reranked.subList(0, Math.min(topK, reranked.size())));
                rerankMs = System.currentTimeMillis() - rerankStart;
            }

            long totalMs = System.currentTimeMillis() - t0;
            SearchTrace trace = new SearchTrace(embedMs, denseMs, lexicalMs, fusionMs, totalMs,
                    searcher.generation(), rerankMs, rerankRequested ? fused.size() : null);
            QueryUsage queryUsage = new QueryUsage(totalMs, embedMs, queryInputChars, 0, embedSkipped, embedCached);
            return new SearchResponse(hits, trace, queryUsage);

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            if (cause instanceof EmbeddingUnavailableException eue) {
                throw eue;
            }
            if (cause instanceof RerankUnavailableException rue) {
                throw rue;
            }
            throw new RuntimeException("Search failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Search interrupted", e);
        }
    }

    public void reindex(String tenant) throws IOException {
        Object lock = rebuildLocks.computeIfAbsent(tenant, t -> new Object());
        synchronized (lock) {
            log.info("Reindexing tenant '{}'...", tenant);
            AtomicReference<HybridSearcher> ref = searcherRefs.get(tenant);
            if (ref != null && ref.get() != null) {
                ref.get().close();
                ref.set(null);
            }
            indexBuilder.build(tenant);
            HybridSearcher fresh = new HybridSearcher(indexBuilder.indexPath(tenant), props.embedding().dim());
            searcherRefs.computeIfAbsent(tenant, t -> new AtomicReference<>()).set(fresh);
            log.info("Reindex complete for tenant '{}'", tenant);
        }
    }

    public IndexStats stats(String tenant) throws IOException {
        HybridSearcher searcher = getSearcher(tenant);
        if (searcher == null) {
            return new IndexStats(tenant, 0, -1, status.name().toLowerCase());
        }
        var report = indexBuilder.lastReport(tenant).orElse(null);
        if (report == null) {
            return new IndexStats(tenant, searcher.docCount(), searcher.generation(), "ready");
        }
        return new IndexStats(tenant, searcher.docCount(), searcher.generation(), "ready",
                report.embeddingModel(), report.embeddingDim(), report.truncated(),
                report.vectorDocs(), report.dimMismatches(), report.missingVectors());
    }

    public Status getStatus() {
        return status;
    }

    private HybridSearcher getSearcher(String tenant) {
        AtomicReference<HybridSearcher> ref = searcherRefs.get(tenant);
        return ref != null ? ref.get() : null;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value) {
        return "true".equalsIgnoreCase(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<String> parseSourceElements(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
