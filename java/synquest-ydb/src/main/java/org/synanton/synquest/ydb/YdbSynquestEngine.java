package org.synanton.synquest.ydb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
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
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.DataQueryResult;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.settings.ExecuteDataQuerySettings;
import tech.ydb.table.transaction.TxControl;
import tech.ydb.table.values.PrimitiveValue;

/**
 * 024B YDB retrieval adapter — commit 1: lexical leg only (vector/hybrid follow
 * in commits 2–3). Reads the 024B projection table through an unfiltered
 * `fulltext_relevance` index + tenant equality (Gate 0 pattern: filtered FT is
 * flag-disabled on this build). Pre-ranking eligibility via the mandatory
 * tenant predicate composed into candidate generation.
 */
public class YdbSynquestEngine
        implements SynquestEngine, SynquestIndexWriter, SynquestIndexAdmin, Conformant {

    private final TableClient client;
    private final String prefix;
    private final AdapterMetrics metrics;
    private final ConcurrentHashMap<String, GenerationId> generations = new ConcurrentHashMap<>();

    public YdbSynquestEngine(TableClient client, String tablePrefix) {
        this(client, tablePrefix, new InMemoryAdapterMetrics("ydb@1.0.0"));
    }

    public YdbSynquestEngine(TableClient client, String tablePrefix, AdapterMetrics metrics) {
        this.client = client;
        this.prefix = tablePrefix;
        this.metrics = metrics;
    }

    private String table() {
        return "`" + prefix + "_projections`";
    }

    private String vectors() {
        return "`" + prefix + "_vectors`";
    }

    /**
     * Single-column PK value for the vectors table (HybridRank requires a
     * single-column primary key). Chunk/doc ids in this PoC never contain '|'.
     */
    static String keyOf(String tenant, ChunkId chunkId) {
        return tenant + "|" + chunkId.value();
    }

    // ---- writer ----

    @Override
    public CompletionStage<Void> upsert(List<ChunkProjection> projections) {
        long start = System.nanoTime();
        try (Session session = session()) {
            for (ChunkProjection p : projections) {
                String tenant = p.tenantId();
                Long stored = readOrdering(session, tenant, p.chunkId());
                if (stored != null && p.orderingKey() <= stored) {
                    continue;
                }
                exec(
                        session,
                        "DECLARE $t AS Utf8; DECLARE $c AS Utf8; DECLARE $d AS Utf8;"
                                + " DECLARE $x AS Utf8; DECLARE $m AS Json; DECLARE $o AS Uint64;"
                                + " DECLARE $g AS Utf8;"
                                + "UPSERT INTO " + table()
                                + " (tenant_id, chunk_id, doc_id, chunk_text, metadata_json,"
                                + " ordering_key, generation) VALUES"
                                + " ($t, $c, $d, $x, $m, $o, $g);",
                        Params.create()
                                .put("$t", PrimitiveValue.newText(tenant))
                                .put("$c", PrimitiveValue.newText(p.chunkId().value()))
                                .put("$d", PrimitiveValue.newText(p.documentId().value()))
                                .put("$x", PrimitiveValue.newText(p.text()))
                                .put("$m", PrimitiveValue.newJson(YdbJson.toJson(p.metadata())))
                                .put("$o", PrimitiveValue.newUint64(p.orderingKey()))
                                .put("$g", PrimitiveValue.newText(p.generationId().value())));
                if (p.embedding() != null) {
                    exec(
                            session,
                            "DECLARE $k AS Utf8; DECLARE $t AS Utf8; DECLARE $c AS Utf8; DECLARE $d AS Utf8;"
                                    + " DECLARE $x AS Utf8; DECLARE $m AS Json;"
                                    + " DECLARE $o AS Uint64; DECLARE $g AS Utf8;"
                                    + "UPSERT INTO " + vectors()
                                    + " (key, tenant_id, chunk_id, doc_id, chunk_text, metadata_json, embedding,"
                                    + " ordering_key, generation) VALUES"
                                    + " ($k, $t, $c, $d, $x, $m, Untag(Knn::ToBinaryStringFloat(["
                                    + floatList(p.embedding()) + "]), 'FloatVector'),"
                                    + " $o, $g);",
                            Params.create()
                                    .put("$k", PrimitiveValue.newText(keyOf(tenant, p.chunkId())))
                                    .put("$t", PrimitiveValue.newText(tenant))
                                    .put("$c", PrimitiveValue.newText(p.chunkId().value()))
                                    .put("$d", PrimitiveValue.newText(p.documentId().value()))
                                    .put("$x", PrimitiveValue.newText(p.text()))
                                    .put("$m", PrimitiveValue.newJson(YdbJson.toJson(p.metadata())))
                                    .put("$o", PrimitiveValue.newUint64(p.orderingKey()))
                                    .put("$g", PrimitiveValue.newText(p.generationId().value())));
                }
            }
            metrics.record(AdapterMetrics.SYNQUEST_UPSERT, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            metrics.record(AdapterMetrics.SYNQUEST_UPSERT, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public CompletionStage<Void> delete(GenerationId generationId, java.util.Collection<ChunkId> ids) {
        long start = System.nanoTime();
        try (Session session = session()) {
            for (ChunkId id : ids) {
                for (String tenant : tenantsWith(session, id)) {
                    Optional<String> generation = readGeneration(session, tenant, id);
                    if (generation.isPresent() && generation.get().equals(generationId.value())) {
                        exec(
                                session,
                                "DECLARE $t AS Utf8; DECLARE $c AS Utf8;"
                                        + "DELETE FROM " + table() + " WHERE tenant_id=$t AND chunk_id=$c;",
                                Params.create()
                                        .put("$t", PrimitiveValue.newText(tenant))
                                        .put("$c", PrimitiveValue.newText(id.value())));
                        exec(
                                session,
                                "DECLARE $k AS Utf8;"
                                        + "DELETE FROM " + vectors() + " WHERE key=$k;",
                                Params.create()
                                        .put("$k", PrimitiveValue.newText(keyOf(tenant, id))));
                    }
                }
            }
            metrics.record(AdapterMetrics.SYNQUEST_DELETE, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            metrics.record(AdapterMetrics.SYNQUEST_DELETE, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    // ---- retrieval (commit 1: lexical only) ----

    // ---- retrieval (commit 2: lexical + vector; hybrid in commit 3) ----

    @Override
    public CompletionStage<SearchResult> search(SecurityContext context, SearchRequest request) {
        long start = System.nanoTime();
        if (!request.temporal().isEmpty() && !capabilities().temporal()) {
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, false);
            return CompletableFuture.failedFuture(
                    new StorageException(
                            StorageErrorKind.UNSUPPORTED,
                            "UNSUPPORTED: temporal retrieval not supported by this adapter"));
        }
        try (Session session = session()) {
            String tenant = tenant(context, request);
            if (request.mode() == SearchMode.HYBRID) {
                return hybridSearch(session, tenant, request, start);
            }
            SearchResult result =
                    request.mode() == SearchMode.VECTOR
                            ? vectorSearch(session, tenant, request)
                            : lexicalSearch(session, tenant, request);
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException e) {
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    private CompletionStage<SearchResult> hybridSearch(
            Session session, String tenant, SearchRequest request, long start) {
        try {
            String terms = escapeQuotes(request.queryText());
            String queryVec =
                    request.queryEmbedding().isPresent()
                            ? floatList(request.queryEmbedding().get())
                            : null;
            if (queryVec == null) {
                metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, false);
                return CompletableFuture.failedFuture(
                        new StorageException(
                                StorageErrorKind.UNSUPPORTED,
                                "UNSUPPORTED: hybrid requires a query embedding"));
            }
            DataQueryResult result =
                    query(
                            session,
                            "DECLARE $t AS Utf8;"
                                    + "SELECT chunk_id, doc_id, chunk_text, metadata_json FROM "
                                    + vectors()
                                    + " WHERE tenant_id=$t"
                                    + " ORDER BY HybridRank(FulltextScore(chunk_text, \"" + terms + "\"),"
                                    + " Knn::CosineDistance(embedding,"
                                    + " Knn::ToBinaryStringFloat([" + queryVec + "])),"
                                    + " (\"v_ft\", \"v_hyb\") AS Indexes)"
                                    + " LIMIT " + (request.topK() + 1) + ";",
                            Params.create().put("$t", PrimitiveValue.newText(tenant)),
                            TxControl.snapshotRo().setCommitTx(true));
            SearchResult collected = collectHybrid(request, result);
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(collected);
        } catch (RuntimeException e) {
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    private SearchResult collectHybrid(SearchRequest request, DataQueryResult result) {
        List<SearchHit> hits = new ArrayList<>();
        Map<ChunkId, String> highlights = new java.util.HashMap<>();
        ResultSetReader rs = result.getResultSet(0);
        int eligible = 0;
        int position = 0;
        while (rs.next()) {
            eligible++;
            Map<String, String> metadata = YdbJson.fromJson(rs.getColumn("metadata_json").getJson());
            if (!matches(metadata, request.filters().mustMatchMetadata())) {
                continue;
            }
            // Rank-derived RRF-shape score (K=60 default): HybridRank cannot be projected.
            double score = 1.0 / (60.0 + position);
            position++;
            if (score < request.minScore()) {
                continue;
            }
            ChunkId chunkId = ChunkId.of(rs.getColumn("chunk_id").getText());
            String text = rs.getColumn("chunk_text").getText();
            hits.add(
                    new SearchHit(
                            chunkId, DocumentId.of(rs.getColumn("doc_id").getText()), score, text, metadata));
            if (hits.size() >= request.topK()) {
                break;
            }
            highlights.put(chunkId, snippet(text, request.queryText()));
        }
        for (SearchHit hit : hits) {
            highlights.putIfAbsent(hit.chunkId(), snippet(hit.text(), request.queryText()));
        }
        return new SearchResult(hits, eligible, highlights);
    }

    private SearchResult lexicalSearch(Session session, String tenant, SearchRequest request) {
        String terms = escapeQuotes(request.queryText());
        DataQueryResult result =
                query(
                        session,
                        "DECLARE $t AS Utf8;"
                                + "SELECT chunk_id, doc_id, chunk_text, metadata_json,"
                                + " FulltextScore(chunk_text, \"" + terms + "\") AS relevance FROM "
                                + table() + " VIEW `ft`"
                                + " WHERE tenant_id=$t AND FulltextScore(chunk_text, \"" + terms + "\") > 0"
                                + " ORDER BY relevance DESC LIMIT " + (request.topK() + 1) + ";",
                        Params.create().put("$t", PrimitiveValue.newText(tenant)),
                        TxControl.snapshotRo().setCommitTx(true));
        return collect(session, request, result);
    }

    private SearchResult vectorSearch(Session session, String tenant, SearchRequest request) {
        if (request.queryEmbedding().isEmpty()) {
            return new SearchResult(List.of(), 0, Map.of());
        }
        String similarity =
                "Knn::CosineSimilarity(embedding,"
                        + " Knn::ToBinaryStringFloat([" + floatList(request.queryEmbedding().get()) + "]))";
        DataQueryResult result =
                query(
                        session,
                        "DECLARE $t AS Utf8;"
                                + "SELECT chunk_id, doc_id, metadata_json, " + similarity + " AS relevance FROM "
                                + vectors() + " VIEW `v_vec`"
                                + " WHERE tenant_id=$t"
                                + " ORDER BY " + similarity + " DESC LIMIT " + (request.topK() + 1) + ";",
                        Params.create().put("$t", PrimitiveValue.newText(tenant)),
                        TxControl.snapshotRo().setCommitTx(true));
        List<SearchHit> hits = new ArrayList<>();
        Map<ChunkId, String> highlights = new java.util.HashMap<>();
        ResultSetReader rs = result.getResultSet(0);
        int eligible = 0;
        while (rs.next()) {
            eligible++;
            Map<String, String> metadata = YdbJson.fromJson(rs.getColumn("metadata_json").getJson());
            if (!matches(metadata, request.filters().mustMatchMetadata())) {
                continue;
            }
            double score = readRelevance(rs);
            if (score < request.minScore()) {
                continue;
            }
            // Text lives in the projections table; vector rows carry ids + metadata.
            ChunkId chunkId = ChunkId.of(rs.getColumn("chunk_id").getText());
            String text = readText(session, tenant, chunkId);
            hits.add(
                    new SearchHit(
                            chunkId, DocumentId.of(rs.getColumn("doc_id").getText()), score, text, metadata));
            if (hits.size() >= request.topK()) {
                break;
            }
            highlights.put(chunkId, snippet(text, request.queryText()));
        }
        for (SearchHit hit : hits) {
            highlights.putIfAbsent(hit.chunkId(), snippet(hit.text(), request.queryText()));
        }
        return new SearchResult(hits, eligible, highlights);
    }

    /**
     * Reads the similarity score tolerantly: the planner may return it as Double
     * or Float depending on the index path taken.
     */
    private static double readRelevance(ResultSetReader rs) {
        try {
            return rs.getColumn("relevance").getDouble();
        } catch (RuntimeException e) {
            return rs.getColumn("relevance").getFloat();
        }
    }

    private SearchResult collect(Session session, SearchRequest request, DataQueryResult result) {
        List<SearchHit> hits = new ArrayList<>();
        Map<ChunkId, String> highlights = new java.util.HashMap<>();
        ResultSetReader rs = result.getResultSet(0);
        int eligible = 0;
        while (rs.next()) {
            eligible++;
            Map<String, String> metadata = YdbJson.fromJson(rs.getColumn("metadata_json").getJson());
            if (!matches(metadata, request.filters().mustMatchMetadata())) {
                continue;
            }
            double score = rs.getColumn("relevance").getDouble();
            if (score < request.minScore()) {
                continue;
            }
            ChunkId chunkId = ChunkId.of(rs.getColumn("chunk_id").getText());
            String text = rs.getColumn("chunk_text").getText();
            hits.add(
                    new SearchHit(
                            chunkId, DocumentId.of(rs.getColumn("doc_id").getText()), score, text, metadata));
            if (hits.size() >= request.topK()) {
                break;
            }
            highlights.put(chunkId, snippet(text, request.queryText()));
        }
        // Highlights for the last hit when topK-bounded above.
        for (SearchHit hit : hits) {
            highlights.putIfAbsent(hit.chunkId(), snippet(hit.text(), request.queryText()));
        }
        return new SearchResult(hits, eligible, highlights);
    }

    private String readText(Session session, String tenant, ChunkId chunkId) {
        DataQueryResult result =
                query(
                        session,
                        "DECLARE $t AS Utf8; DECLARE $c AS Utf8;"
                                + "SELECT chunk_text FROM " + table()
                                + " WHERE tenant_id=$t AND chunk_id=$c;",
                        Params.create()
                                .put("$t", PrimitiveValue.newText(tenant))
                                .put("$c", PrimitiveValue.newText(chunkId.value())),
                        TxControl.snapshotRo().setCommitTx(true));
        ResultSetReader rs = result.getResultSet(0);
        return rs.next() ? rs.getColumn("chunk_text").getText() : "";
    }

    static String floatList(float[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("CAST(").append(Float.toString(values[i])).append(" AS Float)");
        }
        return sb.toString();
    }

    @Override
    public SearchCapabilities capabilities() {
        // Commit 3: all three legs. Temporal/graph stay out of PoC scope.
        return new SearchCapabilities(true, true, true, true, true, false, false, false);
    }

    // ---- admin ----

    @Override
    public CompletionStage<Void> ensureSchema(SchemaOptions options) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> rebuild(RebuildOptions options) {
        generations.put("*", options.targetGeneration());
        if (options.full()) {
            long start = System.nanoTime();
            try (Session session = session()) {
                DataQueryResult tenants =
                        query(
                                session,
                                "SELECT DISTINCT tenant_id FROM " + table() + ";",
                                Params.empty(),
                                TxControl.staleRo().setCommitTx(true));
                ResultSetReader rs = tenants.getResultSet(0);
                while (rs.next()) {
                    String tenant = rs.getColumn("tenant_id").getText();
                    exec(
                            session,
                            "DECLARE $t AS Utf8;"
                                    + "DELETE FROM " + table() + " WHERE tenant_id=$t;",
                            Params.create().put("$t", PrimitiveValue.newText(tenant)));
                }
                metrics.record(AdapterMetrics.SYNQUEST_REBUILD, System.nanoTime() - start, true);
            } catch (RuntimeException e) {
                metrics.record(AdapterMetrics.SYNQUEST_REBUILD, System.nanoTime() - start, false);
                throw map(e);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<IndexStatus> status() {
        try (Session session = session()) {
            DataQueryResult result =
                    query(
                            session,
                            "SELECT COUNT(*) AS n FROM " + table() + ";",
                            Params.empty(),
                            TxControl.staleRo().setCommitTx(true));
            ResultSetReader rs = result.getResultSet(0);
            long count = rs.next() ? rs.getColumn("n").getUint64() : 0;
            return CompletableFuture.completedFuture(
                    new IndexStatus(generations.getOrDefault("*", GenerationId.initial()), count, true));
        } catch (RuntimeException e) {
            throw map(e);
        }
    }

    // ---- Conformant ----

    @Override
    public String adapterName() {
        return "ydb";
    }

    @Override
    public String adapterVersion() {
        return "1.0.0";
    }

    @Override
    public ConformanceMatrix conformance() {
        String evidence = "org.synanton.synquest.ydb.YdbSynquestEngineTest";
        return new ConformanceMatrix(
                adapterName(),
                adapterVersion(),
                List.of(
                        ConformanceEntry.supported(Capabilities.SYNQUEST_LEXICAL, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_VECTOR, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_HYBRID, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_FILTERS, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_HIGHLIGHTS, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_ELIGIBILITY, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_TEMPORAL_REJECTION, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_ORDERING, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_GENERATION_DELETE, evidence)));
    }

    // ---- internals ----

    private Session session() {
        return client.createSession(java.time.Duration.ofSeconds(10)).join().getValue();
    }

    private DataQueryResult query(Session session, String yql, Params params, TxControl<?> control) {
        return session
                .executeDataQuery(yql, control, params, new ExecuteDataQuerySettings())
                .join()
                .getValue();
    }

    private void exec(Session session, String yql, Params params) {
        query(session, yql, params, TxControl.serializableRw().setCommitTx(true));
    }

    private String tenant(SecurityContext context, SearchRequest request) {
        if (!context.service()) {
            return context.tenantScope().tenantId();
        }
        return request.eligibility().tenantScope().tenantId();
    }

    private Long readOrdering(Session session, String tenant, ChunkId id) {
        DataQueryResult result =
                query(
                        session,
                        "DECLARE $t AS Utf8; DECLARE $c AS Utf8;"
                                + "SELECT ordering_key FROM " + table()
                                + " WHERE tenant_id=$t AND chunk_id=$c;",
                        Params.create()
                                .put("$t", PrimitiveValue.newText(tenant))
                                .put("$c", PrimitiveValue.newText(id.value())),
                        TxControl.snapshotRo().setCommitTx(true));
        ResultSetReader rs = result.getResultSet(0);
        return rs.next() ? (long) rs.getColumn("ordering_key").getUint64() : null;
    }

    private Optional<String> readGeneration(Session session, String tenant, ChunkId id) {
        DataQueryResult result =
                query(
                        session,
                        "DECLARE $t AS Utf8; DECLARE $c AS Utf8;"
                                + "SELECT generation FROM " + table()
                                + " WHERE tenant_id=$t AND chunk_id=$c;",
                        Params.create()
                                .put("$t", PrimitiveValue.newText(tenant))
                                .put("$c", PrimitiveValue.newText(id.value())),
                        TxControl.snapshotRo().setCommitTx(true));
        ResultSetReader rs = result.getResultSet(0);
        return rs.next() ? Optional.of(rs.getColumn("generation").getText()) : Optional.empty();
    }

    private List<String> tenantsWith(Session session, ChunkId id) {
        DataQueryResult result =
                query(
                        session,
                        "DECLARE $c AS Utf8;"
                                + "SELECT DISTINCT tenant_id FROM " + table() + " WHERE chunk_id=$c;",
                        Params.create().put("$c", PrimitiveValue.newText(id.value())),
                        TxControl.snapshotRo().setCommitTx(true));
        List<String> out = new ArrayList<>();
        ResultSetReader rs = result.getResultSet(0);
        while (rs.next()) {
            out.add(rs.getColumn("tenant_id").getText());
        }
        return out;
    }

    private static RuntimeException map(RuntimeException e) {
        if (e instanceof StorageException) {
            return e;
        }
        return new StorageException(StorageErrorKind.TRANSIENT, "YDB failure: " + e.getMessage());
    }

    private static boolean matches(Map<String, String> metadata, Map<String, String> required) {
        for (Map.Entry<String, String> entry : required.entrySet()) {
            if (!entry.getValue().equals(metadata.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static String escapeQuotes(String text) {
        return text.replace("\"", " ");
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
