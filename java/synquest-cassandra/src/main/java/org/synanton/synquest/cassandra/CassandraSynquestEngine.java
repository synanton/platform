package org.synanton.synquest.cassandra;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.synanton.storage.contract.AdapterMetrics;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.contract.ConformanceEntry;
import org.synanton.storage.contract.ConformanceMatrix;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.GenerationId;
import org.synanton.storage.contract.InMemoryAdapterMetrics;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.StorageErrorKind;
import org.synanton.storage.contract.StorageException;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.EligibilityScope;
import org.synanton.synquest.api.IndexStatus;
import org.synanton.synquest.api.RebuildOptions;
import org.synanton.synquest.api.SchemaOptions;
import org.synanton.synquest.api.SearchCapabilities;
import org.synanton.synquest.api.SearchHit;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SearchResult;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.SynquestIndexAdmin;
import org.synanton.synquest.api.SynquestIndexWriter;
/**
 * 024A: Cassandra-named retrieval adapter over the current ingestion-cache-backed
 * Lucene path (there is no Cassandra search implementation to move — the name
 * carries the comparison leg, the technology mirrors the production service:
 * per-tenant Lucene indexes, BM25 defaults, KNN cosine, RRF fusion).
 *
 * <p>Mirrors {@code LuceneIndexBuilder}'s field schema ({@code text},
 * {@code embedding}) deliberately; convergence with the service module is
 * tracked (any field-schema change there must be mirrored here until 040
 * retires the duplication).
 */
public class CassandraSynquestEngine
        implements SynquestEngine, SynquestIndexWriter, SynquestIndexAdmin, Conformant {

    private static final int RRF_K = 60;

    private final Path root;
    private final AdapterMetrics metrics;
    private final ConcurrentHashMap<String, SearcherManager> searchers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GenerationId> generations = new ConcurrentHashMap<>();

    public CassandraSynquestEngine(Path root) {
        this(root, new InMemoryAdapterMetrics("cassandra@1.0.0"));
    }

    public CassandraSynquestEngine(Path root, AdapterMetrics metrics) {
        this.root = root;
        this.metrics = metrics;
    }

    private <T> CompletionStage<T> track(String operation, CompletionStage<T> stage) {
        long start = System.nanoTime();
        return stage.whenComplete(
                (value, error) -> metrics.record(operation, System.nanoTime() - start, error == null));
    }

    // ---- writer ----

    @Override
    public CompletionStage<Void> upsert(List<ChunkProjection> projections) {
        long start = System.nanoTime();
        try {
            for (ChunkProjection p : projections) {
                GenerationId active = generations.get("*");
                if (active == null) {
                    generations.putIfAbsent("*", p.generationId());
                    active = generations.get("*");
                }
                if (!p.generationId().equals(active)) {
                    metrics.record(AdapterMetrics.SYNQUEST_UPSERT, System.nanoTime() - start, false);
                    return CompletableFuture.failedFuture(
                            new StorageException(
                                    StorageErrorKind.CONFLICT,
                                    "CONFLICT: stale generation '" + p.generationId().value()
                                            + "', active is '" + active.value() + "'"));
                }
                Long stored = readOrdering(p.tenantId(), p.chunkId());
                if (stored != null && p.orderingKey() <= stored) {
                    continue;
                }
                try (IndexWriter writer = writer(p.tenantId())) {
                    writer.updateDocument(new Term("id", p.chunkId().value()), toDoc(p));
                    writer.commit();
                }
                refresh(p.tenantId());
            }
            metrics.record(AdapterMetrics.SYNQUEST_UPSERT, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            metrics.record(AdapterMetrics.SYNQUEST_UPSERT, System.nanoTime() - start, false);
            throw new StorageException(StorageErrorKind.TRANSIENT, "Lucene upsert failed: " + e.getMessage());
        }
    }

    @Override
    public CompletionStage<Void> delete(GenerationId generationId, Collection<ChunkId> ids) {
        long start = System.nanoTime();
        try {
            Map<String, List<Term>> byTenant = new HashMap<>();
            for (ChunkId id : ids) {
                String tenant = findTenant(id);
                if (tenant == null) {
                    continue;
                }
                if (!generationOf(tenant, id).equals(generationId)) {
                    continue;
                }
                byTenant.computeIfAbsent(tenant, t -> new ArrayList<>()).add(new Term("id", id.value()));
            }
            for (Map.Entry<String, List<Term>> entry : byTenant.entrySet()) {
                try (IndexWriter writer = writer(entry.getKey())) {
                    for (Term term : entry.getValue()) {
                        writer.deleteDocuments(term);
                    }
                    writer.commit();
                }
                refresh(entry.getKey());
            }
            metrics.record(AdapterMetrics.SYNQUEST_DELETE, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            metrics.record(AdapterMetrics.SYNQUEST_DELETE, System.nanoTime() - start, false);
            throw new StorageException(StorageErrorKind.TRANSIENT, "Lucene delete failed: " + e.getMessage());
        }
    }

    // ---- retrieval ----

    @Override
    public CompletionStage<SearchResult> search(SecurityContext context, SearchRequest request) {
        long start = System.nanoTime();
        try {
            if (!request.temporal().isEmpty() && !capabilities().temporal()) {
                metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, false);
                return CompletableFuture.failedFuture(
                        new StorageException(
                                StorageErrorKind.UNSUPPORTED,
                                "UNSUPPORTED: temporal retrieval not supported by this adapter"));
            }
            // P0-3: fail fast on scope mismatch (throws FORBIDDEN) — service
            // contexts can no longer sweep all tenants.
            List<String> tenants =
                    List.of(
                            EligibilityScope.effectiveTenant(context, request.eligibility())
                                    .tenantId());
            List<Scored> eligible = new ArrayList<>();
            for (String tenant : tenants) {
                ensureSearcher(tenant);
                SearcherManager manager = searchers.get(tenant);
                if (manager == null) {
                    continue;
                }
                IndexSearcher searcher = manager.acquire();
                try {
                    searcher.setSimilarity(new BM25Similarity());
                    switch (request.mode()) {
                        case LEXICAL -> {
                            TopDocs lexical = lexical(searcher, request);
                            for (int rank = 0; rank < lexical.scoreDocs.length; rank++) {
                                ScoreDoc sd = lexical.scoreDocs[rank];
                                Document doc = searcher.storedFields().document(sd.doc);
                                if (!matches(metaOf(doc), request.filters().mustMatchMetadata())) {
                                    continue;
                                }
                                if (!isActiveGeneration(doc)) {
                                    continue;
                                }
                                if (sd.score >= request.minScore()) {
                                    eligible.add(hitOf(doc, sd.score));
                                }
                            }
                        }
                        case VECTOR -> {
                            TopDocs dense = dense(searcher, request);
                            for (ScoreDoc sd : dense.scoreDocs) {
                                Document doc = searcher.storedFields().document(sd.doc);
                                if (!matches(metaOf(doc), request.filters().mustMatchMetadata())) {
                                    continue;
                                }
                                if (!isActiveGeneration(doc)) {
                                    continue;
                                }
                                if (sd.score >= request.minScore()) {
                                    eligible.add(hitOf(doc, sd.score));
                                }
                            }
                        }
                        case HYBRID -> {
                            TopDocs lexical = lexical(searcher, request);
                            TopDocs dense = dense(searcher, request);
                            double max = 1e-9;
                            for (ScoreDoc sd : lexical.scoreDocs) {
                                max = Math.max(max, sd.score);
                            }
                            for (ScoreDoc sd : dense.scoreDocs) {
                                max = Math.max(max, sd.score);
                            }
                            for (Fused f : fuse(lexical, dense, Integer.MAX_VALUE)) {
                                Document doc = searcher.storedFields().document(f.doc);
                                if (!matches(metaOf(doc), request.filters().mustMatchMetadata())) {
                                    continue;
                                }
                                if (!isActiveGeneration(doc)) {
                                    continue;
                                }
                                double score = f.rrf() / max;
                                if (score >= request.minScore()) {
                                    eligible.add(hitOf(doc, score));
                                }
                            }
                        }
                    }
                } finally {
                    manager.release(searcher);
                }
            }
            eligible.sort(
                    Comparator.comparingDouble(Scored::score)
                            .reversed()
                            .thenComparing(s -> s.chunkId().value()));
            List<SearchHit> hits =
                    eligible.stream()
                            .limit(request.topK())
                            .map(s -> new SearchHit(s.chunkId(), s.documentId(), s.score(), s.text(), s.metadata()))
                            .toList();
            Map<ChunkId, String> highlights = new HashMap<>();
            for (SearchHit hit : hits) {
                highlights.put(hit.chunkId(), snippet(hit.text(), request.queryText()));
            }
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(new SearchResult(hits, eligible.size(), highlights));
        } catch (Exception e) {
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, false);
            if (e instanceof StorageException) {
                return CompletableFuture.failedFuture(e);
            }
            return CompletableFuture.failedFuture(
                    new StorageException(StorageErrorKind.TRANSIENT, "Lucene search failed: " + e.getMessage()));
        }
    }

    @Override
    public SearchCapabilities capabilities() {
        return SearchCapabilities.pocBaseline();
    }

    // ---- admin ----

    @Override
    public CompletionStage<Void> ensureSchema(SchemaOptions options) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> rebuild(RebuildOptions options) {
        long start = System.nanoTime();
        try {
            for (String tenant : new HashSet<>(searchers.keySet())) {
                if (options.full()) {
                    try (IndexWriter writer = writer(tenant)) {
                        writer.deleteAll();
                        writer.commit();
                    }
                }
                generations.put(tenant, options.targetGeneration());
                refresh(tenant);
            }
            // P0-1: rebuild always flips the global pointer (put, not putIfAbsent).
            generations.put("*", options.targetGeneration());
            metrics.record(AdapterMetrics.SYNQUEST_REBUILD, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            metrics.record(AdapterMetrics.SYNQUEST_REBUILD, System.nanoTime() - start, false);
            return CompletableFuture.failedFuture(
                    new StorageException(StorageErrorKind.TRANSIENT, "Lucene rebuild failed: " + e.getMessage()));
        }
    }

    @Override
    public CompletionStage<IndexStatus> status() {
        long count = 0;
        for (SearcherManager manager : searchers.values()) {
            try {
                IndexSearcher searcher = manager.acquire();
                try {
                    count += searcher.getIndexReader().numDocs();
                } finally {
                    manager.release(searcher);
                }
            } catch (IOException e) {
                return CompletableFuture.completedFuture(
                        new IndexStatus(activeGeneration(), count, false));
            }
        }
        return CompletableFuture.completedFuture(new IndexStatus(activeGeneration(), count, true));
    }

    // ---- Conformant ----

    @Override
    public String adapterName() {
        return "cassandra";
    }

    @Override
    public String adapterVersion() {
        return "1.0.0";
    }

    @Override
    public ConformanceMatrix conformance() {
        String evidence = "org.synanton.synquest.cassandra.CassandraSynquestEngineTest";
        return new ConformanceMatrix(
                adapterName(),
                adapterVersion(),
                List.of(
                        ConformanceEntry.supported(Capabilities.SYNQUEST_LEXICAL, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_VECTOR, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_HYBRID, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_FILTERS, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_HIGHLIGHTS, evidence),
                        ConformanceEntry.partial(Capabilities.SYNQUEST_ELIGIBILITY, "tenant", evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_TEMPORAL_REJECTION, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_ORDERING, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_GENERATION_DELETE, evidence)));
    }

    // ---- internals ----

    private record Scored(
            ChunkId chunkId, DocumentId documentId, String text, Map<String, String> metadata,
            double score) {}

    private static Scored hitOf(Document doc, double score) {
        return new Scored(
                ChunkId.of(doc.get("id")),
                DocumentId.of(doc.get("doc")),
                doc.get("text"),
                metaOf(doc),
                score);
    }

    private record Fused(int doc, double rrf, double dense, double lex) {}

    private Path dir(String tenant) {
        return root.resolve("tenant-" + tenant);
    }

    private IndexWriter writer(String tenant) throws IOException {
        Files.createDirectories(dir(tenant));
        return new IndexWriter(
                FSDirectory.open(dir(tenant)), new IndexWriterConfig(new StandardAnalyzer()));
    }

    private void refresh(String tenant) throws IOException {
        ensureSearcher(tenant);
        SearcherManager manager = searchers.get(tenant);
        if (manager != null) {
            manager.maybeRefresh();
        }
    }

    private void ensureSearcher(String tenant) throws IOException {
        if (!searchers.containsKey(tenant) && Files.exists(dir(tenant))) {
            searchers.put(tenant, new SearcherManager(FSDirectory.open(dir(tenant)), null));
        }
    }

    private Document toDoc(ChunkProjection p) {
        Document doc = new Document();
        doc.add(new StringField("id", p.chunkId().value(), Field.Store.YES));
        doc.add(new StringField("doc", p.documentId().value(), Field.Store.YES));
        doc.add(new TextField("text", p.text(), Field.Store.YES));
        doc.add(new NumericDocValuesField("ordering", p.orderingKey()));
        doc.add(new StoredField("ordering", p.orderingKey()));
        doc.add(new StringField("generation", p.generationId().value(), Field.Store.YES));
        for (Map.Entry<String, String> e : p.metadata().entrySet()) {
            doc.add(new StoredField("meta_" + e.getKey(), e.getValue()));
        }
        if (p.embedding() != null) {
            doc.add(new KnnFloatVectorField("embedding", p.embedding(), VectorSimilarityFunction.COSINE));
        }
        return doc;
    }

    private Long readOrdering(String tenant, ChunkId id) throws IOException {
        SearcherManager manager = searchers.get(tenant);
        if (manager == null) {
            return null;
        }
        IndexSearcher searcher = manager.acquire();
        try {
            TopDocs top =
                    searcher.search(
                            new org.apache.lucene.search.TermQuery(new Term("id", id.value())), 1);
            if (top.scoreDocs.length == 0) {
                return null;
            }
            Document doc = searcher.storedFields().document(top.scoreDocs[0].doc);
            String ordering = doc.get("ordering");
            return ordering == null ? null : Long.parseLong(ordering);
        } finally {
            manager.release(searcher);
        }
    }

    private GenerationId generationOf(String tenant, ChunkId id) {
        try {
            SearcherManager manager = searchers.get(tenant);
            if (manager == null) {
                return GenerationId.of("__absent__");
            }
            IndexSearcher searcher = manager.acquire();
            try {
                TopDocs top =
                        searcher.search(new org.apache.lucene.search.TermQuery(new Term("id", id.value())), 1);
                if (top.scoreDocs.length == 0) {
                    return GenerationId.of("__absent__");
                }
                return GenerationId.of(
                        searcher.storedFields().document(top.scoreDocs[0].doc).get("generation"));
            } finally {
                manager.release(searcher);
            }
        } catch (IOException e) {
            throw new StorageException(StorageErrorKind.TRANSIENT, "generation read failed: " + e.getMessage());
        }
    }

    private String findTenant(ChunkId id) throws IOException {
        for (String tenant : searchers.keySet()) {
            SearcherManager manager = searchers.get(tenant);
            IndexSearcher searcher = manager.acquire();
            try {
                TopDocs top =
                        searcher.search(new org.apache.lucene.search.TermQuery(new Term("id", id.value())), 1);
                if (top.scoreDocs.length > 0) {
                    return tenant;
                }
            } finally {
                manager.release(searcher);
            }
        }
        return null;
    }

    private boolean isActiveGeneration(Document doc) {
        GenerationId active = generations.get("*");
        return active == null || active.value().equals(doc.get("generation"));
    }

    private GenerationId activeGeneration() {
        return generations.getOrDefault("*", GenerationId.initial());
    }

    private TopDocs lexical(IndexSearcher searcher, SearchRequest request) throws Exception {
        try {
            var parser =
                    new org.apache.lucene.queryparser.classic.MultiFieldQueryParser(
                            new String[] {"text"}, new StandardAnalyzer());
            return searcher.search(
                    parser.parse(org.apache.lucene.queryparser.classic.MultiFieldQueryParser.escape(request.queryText())),
                    100);
        } catch (org.apache.lucene.queryparser.classic.ParseException e) {
            return TopDocs.merge(0, new TopDocs[0]);
        }
    }

    private TopDocs dense(IndexSearcher searcher, SearchRequest request) throws IOException {
        if (request.queryEmbedding().isEmpty()) {
            return new TopDocs(
                    new org.apache.lucene.search.TotalHits(0, org.apache.lucene.search.TotalHits.Relation.EQUAL_TO),
                    new ScoreDoc[0]);
        }
        KnnFloatVectorQuery query =
                new KnnFloatVectorQuery("embedding", request.queryEmbedding().get(), 100);
        return searcher.search(query, 100);
    }

    private List<Fused> fuse(TopDocs lexical, TopDocs dense, int topK) {
        Map<Integer, double[]> acc = new HashMap<>();
        rankInto(acc, dense, true);
        rankInto(acc, lexical, false);
        List<Fused> out = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : acc.entrySet()) {
            out.add(new Fused(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
        }
        out.sort(Comparator.comparingDouble(Fused::rrf).reversed());
        return out.stream().limit(topK).toList();
    }

    private void rankInto(Map<Integer, double[]> acc, TopDocs top, boolean isDense) {
        for (int rank = 0; rank < top.scoreDocs.length; rank++) {
            ScoreDoc sd = top.scoreDocs[rank];
            double[] slot = acc.computeIfAbsent(sd.doc, k -> new double[3]);
            slot[0] += 1.0 / (RRF_K + rank + 1);
            if (isDense) {
                slot[1] = sd.score;
            } else {
                slot[2] = sd.score;
            }
        }
    }

    private static Map<String, String> metaOf(Document doc) {
        Map<String, String> out = new HashMap<>();
        for (var field : doc.getFields()) {
            if (field.name().startsWith("meta_")) {
                out.put(field.name().substring(5), field.stringValue());
            }
        }
        return Map.copyOf(out);
    }

    private static boolean matches(Map<String, String> metadata, Map<String, String> required) {
        for (Map.Entry<String, String> entry : required.entrySet()) {
            if (!entry.getValue().equals(metadata.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static String snippet(String text, String query) {
        String lower = text.toLowerCase();
        int best = -1;
        for (String token : query.toLowerCase().split("[^a-z0-9]+")) {
            if (token.isBlank()) {
                continue;
            }
            int at = lower.indexOf(token);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        if (best < 0) {
            return text.length() <= 120 ? text : text.substring(0, 120);
        }
        int from = Math.max(0, best - 30);
        int to = Math.min(text.length(), best + 90);
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
    }
}
