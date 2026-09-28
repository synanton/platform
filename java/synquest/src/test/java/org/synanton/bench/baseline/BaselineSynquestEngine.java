package org.synanton.bench.baseline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.synanton.bench.baseline.BaselineIndex.TenantIndex;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.EligibilityScope;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchCapabilities;
import org.synanton.synquest.api.SearchHit;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SearchResult;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.TemporalExtension;
import org.synanton.synquest.service.HybridSearcher;
import org.synanton.synquest.service.RrfFusion;

/**
 * 028b.1b baseline port wrapper: presents production {@code HybridSearcher} +
 * {@code RrfFusion} as a {@code SynquestEngine}. One instance serves the
 * tenants in its {@link TenantIndex} map (028b.3 builds all 50; 028b.1b proves
 * delegation on a mini index).
 *
 * <p>Leg parameters mirror production exactly (lexical/dense top 100, RRF
 * k=60) — the wrapper changes routing, never retrieval shape.
 */
public final class BaselineSynquestEngine implements SynquestEngine {

    static final int TOP_LEX = 100;
    static final int TOP_DENSE = 100;
    static final int RRF_K = 60;

    private final TenantIndex indexes;

    public BaselineSynquestEngine(TenantIndex indexes) {
        this.indexes = indexes;
    }

    @Override
    public SearchCapabilities capabilities() {
        return new SearchCapabilities(true, true, true, true, false, false, false, false);
    }

    @Override
    public CompletionStage<SearchResult> search(SecurityContext context, SearchRequest request) {
        try {
            TenantScope tenant =
                    EligibilityScope.effectiveTenant(context, request.eligibility());
            HybridSearcher searcher = indexes.searcher(tenant.tenantId());
            List<Scored> hits =
                    switch (request.mode()) {
                        case LEXICAL -> lexical(searcher, request);
                        case VECTOR -> vector(searcher, request);
                        case HYBRID -> hybrid(searcher, request);
                    };
            // Eligibility-shaped post-filter (metadata mustMatch) + minScore.
            List<Scored> kept = new ArrayList<>();
            for (Scored s : hits) {
                if (!matches(s.metadata(), request.filters().mustMatchMetadata())) {
                    continue;
                }
                if (s.score() >= request.minScore()) {
                    kept.add(s);
                }
            }
            kept.sort(
                    Comparator.comparingDouble(Scored::score).reversed()
                            .thenComparing(s -> s.chunkId().value()));
            List<SearchHit> out =
                    kept.stream()
                            .limit(request.topK())
                            .map(
                                    s ->
                                            new SearchHit(
                                                    s.chunkId(), s.documentId(), s.score(), s.text(),
                                                    s.metadata()))
                            .toList();
            return CompletableFuture.completedFuture(new SearchResult(out, kept.size(), Map.of()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private record Scored(ChunkId chunkId, DocumentId documentId, double score, String text,
            Map<String, String> metadata) {}

    private static boolean matches(Map<String, String> meta, Map<String, String> required) {
        return required.entrySet().stream()
                .allMatch(e -> e.getValue().equals(meta.getOrDefault(e.getKey(), "")));
    }

    private List<Scored> lexical(HybridSearcher searcher, SearchRequest request) throws Exception {
        TopDocs top = searcher.lexical(request.queryText(), TOP_LEX);
        var stored = searcher.storedFields();
        List<Scored> out = new ArrayList<>();
        for (ScoreDoc sd : top.scoreDocs) {
            var doc = stored.document(sd.doc);
            out.add(
                    new Scored(
                            ChunkId.of(doc.get("id")), DocumentId.of(doc.get("id")),
                            sd.score, doc.get("text"), readMeta(doc)));
        }
        return out;
    }

    private List<Scored> vector(HybridSearcher searcher, SearchRequest request) throws Exception {
        if (request.queryEmbedding().isEmpty()) {
            return List.of();
        }
        TopDocs top = searcher.dense(request.queryEmbedding().get(), TOP_DENSE);
        var stored = searcher.storedFields();
        List<Scored> out = new ArrayList<>();
        for (ScoreDoc sd : top.scoreDocs) {
            var doc = stored.document(sd.doc);
            out.add(
                    new Scored(
                            ChunkId.of(doc.get("id")), DocumentId.of(doc.get("id")),
                            sd.score, doc.get("text"), readMeta(doc)));
        }
        return out;
    }

    private List<Scored> hybrid(HybridSearcher searcher, SearchRequest request) throws Exception {
        TopDocs lex = searcher.lexical(request.queryText(), TOP_LEX);
        TopDocs dense =
                request.queryEmbedding()
                        .map(q -> {
                            try {
                                return searcher.dense(q, TOP_DENSE);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .orElseGet(() -> TopDocs.merge(0, new TopDocs[0]));
        var stored = searcher.storedFields();
        List<Scored> out = new ArrayList<>();
        for (var f : RrfFusion.combine(dense, lex, request.topK(), RRF_K)) {
            var doc = stored.document(f.docId());
            out.add(
                    new Scored(
                            ChunkId.of(doc.get("id")), DocumentId.of(doc.get("id")),
                            f.rrfScore(), doc.get("text"), readMeta(doc)));
        }
        return out;
    }

    /** Stored meta_* fields back into hit metadata (metadata filter legs). */
    private static Map<String, String> readMeta(org.apache.lucene.document.Document doc) {
        Map<String, String> meta = new java.util.LinkedHashMap<>();
        for (org.apache.lucene.index.IndexableField f : doc.getFields()) {
            if (f.name().startsWith("meta_")) {
                meta.put(f.name().substring(5), f.stringValue());
            }
        }
        return Map.copyOf(meta);
    }

    /** Test/CLI helper: port-native request construction for golden queries. */
    public static SearchRequest requestFor(
            String text,
            java.util.Optional<float[]> embedding,
            org.synanton.synquest.api.SearchMode mode,
            TenantScope tenant,
            Map<String, String> metadataFilter,
            int topK) {
        var principal = new org.synanton.storage.contract.PrincipalRef("benchmark", "028b");
        var policy = new org.synanton.storage.contract.PolicyContext("benchmark", "v1");
        return new SearchRequest(
                text,
                embedding,
                java.util.Optional.of(
                        new org.synanton.storage.contract.EmbeddingModelRef("bench", "v1", "bench")),
                mode,
                EligibilityConstraints.from(tenant, List.of(principal), policy),
                new RelevanceFilters(metadataFilter),
                TemporalExtension.empty(),
                topK,
                0.0);
    }
}
