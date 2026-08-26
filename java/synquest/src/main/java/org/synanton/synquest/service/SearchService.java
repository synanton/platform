package org.synanton.synquest.service;

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

    public enum Status { STARTING, READY, ERROR }

    private final LuceneIndexBuilder indexBuilder;
    private final QueryEmbedder queryEmbedder;
    private final SynquestProperties props;

    private final Map<String, AtomicReference<HybridSearcher>> searcherRefs = new ConcurrentHashMap<>();
    private final Map<String, Object> rebuildLocks = new ConcurrentHashMap<>();

    private volatile Status status = Status.STARTING;

    private final ExecutorService searchPool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()));

    public SearchService(LuceneIndexBuilder indexBuilder,
                         QueryEmbedder queryEmbedder,
                         SynquestProperties props) {
        this.indexBuilder = indexBuilder;
        this.queryEmbedder = queryEmbedder;
        this.props = props;
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

        float[] queryVec = null;
        long embedMs = 0;
        try {
            long embedStart = System.currentTimeMillis();
            queryVec = queryEmbedder.embed(req.query());
            embedMs = System.currentTimeMillis() - embedStart;
        } catch (Exception e) {
            log.warn("Query embedding unavailable, using BM25 only: {}", e.getMessage());
        }

        final float[] denseVec = queryVec;
        Future<TopDocs> denseFuture = searchPool.submit(() -> {
            if (denseVec == null) {
                return new TopDocs(new org.apache.lucene.search.TotalHits(0,
                        org.apache.lucene.search.TotalHits.Relation.EQUAL_TO), new org.apache.lucene.search.ScoreDoc[0]);
            }
            try {
                return searcher.dense(denseVec, topKDense);
            } catch (Exception e) {
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

            // RRF fusion
            long fusionStart = System.currentTimeMillis();
            List<RrfFusion.FusedHit> fused = RrfFusion.combine(denseResults, lexicalResults, topK, rrfK);
            long fusionMs = System.currentTimeMillis() - fusionStart;

            // Hydrate hits from stored fields
            var stored = searcher.storedFields();
            List<Hit> hits = new ArrayList<>(fused.size());
            for (RrfFusion.FusedHit fh : fused) {
                Document doc = stored.document(fh.docId());
                String contentRefId = doc.get("content_ref_id");
                int chunkOrdinal = Integer.parseInt(Objects.requireNonNullElse(doc.get("chunk_ordinal"), "0"));
                String text = doc.get("text");
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
                        doc.get("heading")));
            }

            long totalMs = System.currentTimeMillis() - t0;
            SearchTrace trace = new SearchTrace(embedMs, denseMs, lexicalMs, fusionMs, totalMs,
                    searcher.generation());
            return new SearchResponse(hits, trace);

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) throw ioe;
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
            // Close existing searcher
            AtomicReference<HybridSearcher> ref = searcherRefs.get(tenant);
            if (ref != null && ref.get() != null) {
                ref.get().close();
                ref.set(null);
            }
            // Rebuild from scratch
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
        return new IndexStats(tenant, searcher.docCount(), searcher.generation(), "ready");
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
}
