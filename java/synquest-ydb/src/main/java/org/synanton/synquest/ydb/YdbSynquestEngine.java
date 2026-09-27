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
import tech.ydb.common.transaction.TxMode;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.DataQueryResult;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.settings.BeginTxSettings;
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

    private String generations() {
        return "`" + prefix + "_generations`";
    }

    /**
     * Persisted active-generation pointer (P0-1; dissolves P2-3 on this adapter).
     * Absent row = unset (adopt-on-first-write).
     */
    private Optional<String> readActive(Session session) {
        DataQueryResult result =
                query(
                        session,
                        "DECLARE $s AS Utf8;"
                                + "SELECT active_generation FROM " + generations()
                                + " WHERE scope=$s;",
                        Params.create().put("$s", PrimitiveValue.newText("*")),
                        TxControl.snapshotRo().setCommitTx(true));
        ResultSetReader rs = result.getResultSet(0);
        return rs.next() ? Optional.of(rs.getColumn("active_generation").getText()) : Optional.empty();
    }

    private void writeActive(Session session, String generation) {
        exec(
                session,
                "DECLARE $s AS Utf8; DECLARE $g AS Utf8;"
                        + "UPSERT INTO " + generations() + " (scope, active_generation) VALUES ($s, $g);",
                Params.create()
                        .put("$s", PrimitiveValue.newText("*"))
                        .put("$g", PrimitiveValue.newText(generation)));
    }

    // ---- writer ----

    @Override
    public CompletionStage<Void> upsert(List<ChunkProjection> projections) {
        long start = System.nanoTime();
        try (Session session = session()) {
            Optional<String> active = readActive(session);
            for (ChunkProjection p : projections) {
                if (active.isEmpty()) {
                    writeActive(session, p.generationId().value());
                    active = Optional.of(p.generationId().value());
                }
                if (!p.generationId().value().equals(active.get())) {
                    metrics.record(AdapterMetrics.SYNQUEST_UPSERT, System.nanoTime() - start, false);
                    return CompletableFuture.failedFuture(
                            new StorageException(
                                    StorageErrorKind.CONFLICT,
                                    "CONFLICT: stale generation '" + p.generationId().value()
                                            + "', active is '" + active.get() + "'"));
                }
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
            Optional<String> active = readActive(session);
            if (request.mode() == SearchMode.HYBRID) {
                return hybridSearch(session, tenant, request, active, start);
            }
            SearchResult result =
                    request.mode() == SearchMode.VECTOR
                            ? vectorSearch(session, tenant, request, active)
                            : lexicalSearch(session, tenant, request, active);
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException e) {
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    private CompletionStage<SearchResult> hybridSearch(
            Session session, String tenant, SearchRequest request, Optional<String> active, long start) {
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
            Params params = Params.create().put("$t", PrimitiveValue.newText(tenant));
            DataQueryResult result =
                    query(
                            session,
                            "DECLARE $t AS Utf8;"
                                    + "SELECT chunk_id, doc_id, chunk_text, metadata_json, generation FROM "
                                    + vectors()
                                    + " WHERE tenant_id=$t"
                                    + " ORDER BY HybridRank(FulltextScore(chunk_text, \"" + terms + "\"),"
                                    + " Knn::CosineDistance(embedding,"
                                    + " Knn::ToBinaryStringFloat([" + queryVec + "])),"
                                    + " (\"v_ft\", \"v_hyb\") AS Indexes)"
                                    + " LIMIT " + (request.topK() + 1) + ";",
                            params,
                            TxControl.snapshotRo().setCommitTx(true));
            SearchResult collected = collectHybrid(request, result, active);
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(collected);
        } catch (RuntimeException e) {
            metrics.record(AdapterMetrics.SYNQUEST_SEARCH, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    /**
     * Generation predicate for P0-1 filtered reads. Valid ONLY where the VIEW
     * tolerates non-key equality predicates (fulltext VIEW: yes, proven;
     * vector VIEW: no — those paths post-filter in Java, see vectorSearch).
     * Binds $g when active.
     */
    private static String generationClause(Optional<String> active, Params params) {
        if (active.isEmpty()) {
            return "";
        }
        params.put("$g", PrimitiveValue.newText(active.get()));
        return " AND generation=$g";
    }

    private static String declareTenant(Optional<String> active) {
        return active.isPresent() ? "DECLARE $t AS Utf8; DECLARE $g AS Utf8;" : "DECLARE $t AS Utf8;";
    }

    private SearchResult collectHybrid(
            SearchRequest request, DataQueryResult result, Optional<String> active) {
        List<SearchHit> hits = new ArrayList<>();
        Map<ChunkId, String> highlights = new java.util.HashMap<>();
        ResultSetReader rs = result.getResultSet(0);
        int eligible = 0;
        int position = 0;
        while (rs.next()) {
            eligible++;
            if (active.isPresent()
                    && !active.get().equals(rs.getColumn("generation").getText())) {
                continue;
            }            Map<String, String> metadata = YdbJson.fromJson(rs.getColumn("metadata_json").getJson());
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
        return new SearchResult(deterministicOrder(hits, request.topK()), eligible, highlights);
    }

    private SearchResult lexicalSearch(
            Session session, String tenant, SearchRequest request, Optional<String> active) {
        String terms = escapeQuotes(request.queryText());
        Params params = Params.create().put("$t", PrimitiveValue.newText(tenant));
        DataQueryResult result =
                query(
                        session,
                        declareTenant(active)
                                + "SELECT chunk_id, doc_id, chunk_text, metadata_json,"
                                + " FulltextScore(chunk_text, \"" + terms + "\") AS relevance FROM "
                                + table() + " VIEW `ft`"
                                + " WHERE tenant_id=$t" + generationClause(active, params)
                                + " AND FulltextScore(chunk_text, \"" + terms + "\") > 0"
                                + " ORDER BY relevance DESC LIMIT " + (request.topK() + 1) + ";",
                        params,
                        TxControl.snapshotRo().setCommitTx(true));
        return collect(session, request, result);
    }

    private SearchResult vectorSearch(
            Session session, String tenant, SearchRequest request, Optional<String> active) {
        if (request.queryEmbedding().isEmpty()) {
            return new SearchResult(List.of(), 0, Map.of());
        }
        String similarity =
                "Knn::CosineSimilarity(embedding,"
                        + " Knn::ToBinaryStringFloat([" + floatList(request.queryEmbedding().get()) + "]))";
        // P0-1 note: generation filtering is post-retrieval here, not in the VIEW
        // predicate — vector VIEW queries only resolve index-key columns in WHERE.
        // Deterministic (never a security boundary), LIMIT-sized for PoC scale.
        Params params = Params.create().put("$t", PrimitiveValue.newText(tenant));
        DataQueryResult result =
                query(
                        session,
                        "DECLARE $t AS Utf8;"
                                + "SELECT chunk_id, doc_id, metadata_json, generation, " + similarity
                                + " AS relevance FROM "
                                + vectors() + " VIEW `v_vec`"
                                + " WHERE tenant_id=$t"
                                + " ORDER BY " + similarity + " DESC LIMIT " + (request.topK() + 1) + ";",
                        params,
                        TxControl.snapshotRo().setCommitTx(true));
        List<SearchHit> hits = new ArrayList<>();
        Map<ChunkId, String> highlights = new java.util.HashMap<>();
        ResultSetReader rs = result.getResultSet(0);
        int eligible = 0;
        while (rs.next()) {
            eligible++;
            if (active.isPresent()
                    && !active.get().equals(rs.getColumn("generation").getText())) {
                continue;
            }
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
        return new SearchResult(deterministicOrder(hits, request.topK()), eligible, highlights);
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
        return new SearchResult(deterministicOrder(hits, request.topK()), eligible, highlights);
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
        long start = System.nanoTime();
        try (Session session = session()) {
            var tx =
                    session
                            .beginTransaction(TxMode.SERIALIZABLE_RW, new BeginTxSettings())
                            .join()
                            .getValue();
            try {
                if (options.full()) {
                    // Wipe both projection tables, then flip the pointer — one atomic
                    // promotion: readers see the old generation or the new one, never mixed.
                    for (String target : List.of(table(), vectors())) {
                        DataQueryResult tenants =
                                tx.executeDataQuery(
                                                "SELECT DISTINCT tenant_id FROM " + target + ";",
                                                false,
                                                Params.empty(),
                                                new ExecuteDataQuerySettings())
                                        .join()
                                        .getValue();
                        ResultSetReader rs = tenants.getResultSet(0);
                        while (rs.next()) {
                            String tenant = rs.getColumn("tenant_id").getText();
                            tx.executeDataQuery(
                                            "DECLARE $t AS Utf8; DELETE FROM " + target
                                                    + " WHERE tenant_id=$t;",
                                            false,
                                            Params.create().put("$t", PrimitiveValue.newText(tenant)),
                                            new ExecuteDataQuerySettings())
                                    .join()
                                    .getValue();
                        }
                    }
                }
                tx.executeDataQuery(
                                "DECLARE $s AS Utf8; DECLARE $g AS Utf8;"
                                        + "UPSERT INTO " + generations()
                                        + " (scope, active_generation) VALUES ($s, $g);",
                                false,
                                Params.create()
                                        .put("$s", PrimitiveValue.newText("*"))
                                        .put("$g", PrimitiveValue.newText(options.targetGeneration().value())),
                                new ExecuteDataQuerySettings())
                        .join()
                        .getValue();
                tech.ydb.core.Status committed = tx.commit().join();
                if (!committed.isSuccess()) {
                    throw new IllegalStateException("rebuild commit failed: " + committed);
                }
                metrics.record(AdapterMetrics.SYNQUEST_REBUILD, System.nanoTime() - start, true);
                return CompletableFuture.completedFuture(null);
            } catch (RuntimeException e) {
                try {
                    tx.rollback().join();
                } catch (RuntimeException ignored) {
                }
                metrics.record(AdapterMetrics.SYNQUEST_REBUILD, System.nanoTime() - start, false);
                throw e;
            }
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            metrics.record(AdapterMetrics.SYNQUEST_REBUILD, System.nanoTime() - start, false);
            throw map(e);
        }
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
            GenerationId active =
                    readActive(session).map(GenerationId::of).orElse(GenerationId.initial());
            return CompletableFuture.completedFuture(new IndexStatus(active, count, true));
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
                        ConformanceEntry.partial(Capabilities.SYNQUEST_ELIGIBILITY, "tenant", evidence),
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
        // P0-3: effective tenant is always the validated context tenant; a request
        // naming any other tenant is rejected (FORBIDDEN), including service contexts.
        return EligibilityScope.effectiveTenant(context, request.eligibility()).tenantId();
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

    /**
     * Deterministic tie-break (P0 follow-up): server ORDER BY has no secondary
     * key on indexed paths (a second key breaks VIEW matching), so ties are
     * ordered here by (score desc, chunkId asc). Repeated runs over the same
     * data are stable; residual top-K boundary variance on exact ties is noted
     * in preflight, not hidden.
     */
    private static java.util.List<SearchHit> deterministicOrder(
            java.util.List<SearchHit> hits, int topK) {
        return hits.stream()
                .sorted(
                        java.util.Comparator.comparingDouble(SearchHit::score)
                                .reversed()
                                .thenComparing(h -> h.chunkId().value()))
                .limit(topK)
                .toList();
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
