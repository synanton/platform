package org.synanton.synquest.postgres;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.sql.DataSource;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.contract.ConformanceEntry;
import org.synanton.storage.contract.ConformanceMatrix;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.GenerationId;
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
 * PG-POC-007 retrieval adapter (scaffold, 007-1).
 *
 * <p>Implements the full port surface ({@link SynquestEngine}, {@link
 * SynquestIndexWriter}, {@link SynquestIndexAdmin}) with honest
 * {@code UnsupportedOperationException} stubs — retrieval semantics land
 * incrementally in 007-2 onward. Per the §9.3 discipline every capability
 * flag reports {@code false} until evidenced; the conformance matrix marks
 * the retrieval entries {@code unverified}, never supported-by-assertion.
 *
 * <p>Reads the same {@code documents}/{@code chunks}/{@code provenance}
 * tables the {@code synvault-postgres} adapter writes (PG-POC-004); RLS
 * scoping via {@code SET LOCAL} follows the store's shape.
 */
public class PostgresSynquestEngine
        implements SynquestEngine, SynquestIndexWriter, SynquestIndexAdmin, Conformant {

    private static final Logger LOG = Logger.getLogger(PostgresSynquestEngine.class.getName());

    private final DataSource dataSource;

    public PostgresSynquestEngine(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static UnsupportedOperationException todo(String method, String ticket) {
        return new UnsupportedOperationException(
                "PG-POC-007 scaffold (007-1): " + method + " lands in " + ticket);
    }

    // ---- search ----

    @Override
    public CompletionStage<SearchResult> search(SecurityContext context, SearchRequest request) {
        if (!request.temporal().isEmpty() && !capabilities().temporal()) {
            return CompletableFuture.failedFuture(
                    new StorageException(
                            StorageErrorKind.UNSUPPORTED,
                            "UNSUPPORTED: temporal retrieval not supported by this adapter"));
        }
        String tenant;
        try {
            tenant = EligibilityScope.effectiveTenant(context, request.eligibility()).tenantId();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (request.mode() == SearchMode.LEXICAL && capabilities().lexical()) {
            return searchLexical(tenant, request, request.topK());
        }
        if (request.mode() == SearchMode.VECTOR && capabilities().vector()) {
            return searchVector(tenant, request, request.topK());
        }
        if (request.mode() == SearchMode.HYBRID && capabilities().hybrid()) {
            return searchHybrid(tenant, request);
        }
        return CompletableFuture.failedFuture(
                new StorageException(
                        StorageErrorKind.UNSUPPORTED,
                        "UNSUPPORTED: mode " + request.mode() + " lands in 007-3+"));
    }

    /**
     * 007-2 lexical: disjunctive {@code to_tsquery} over the maintained
     * {@code tsv} column, ranked by {@code ts_rank}, metadata pushed into
     * SQL ({@code @>}), RLS-scoped per operation, post-retrieval tie-break
     * ({@code score desc, chunkId asc}) per PG-POC-013.
     *
     * <p>Named choice (ticket): disjunctive mirrors Lucene QueryParser
     * default OR (production parity target); {@code ts_rank} is custom, not
     * Lucene BM25. Both recorded for the 007-4 probe — the semantics travel
     * with the number, never implicit in the code.
     */
    private CompletionStage<SearchResult> searchLexical(String tenant, SearchRequest request, int limit) {
        String tsquery = toDisjunctiveTsquery(request.queryText());
        if (tsquery.isEmpty()) {
            return CompletableFuture.completedFuture(new SearchResult(List.of(), 0, Map.of()));
        }
        try {
            List<SearchHit> matches = fetchLexical(tenant, request, tsquery, limit);
            int total = matches.size();
            List<SearchHit> page = matches.subList(0, Math.min(limit, matches.size()));
            return CompletableFuture.completedFuture(new SearchResult(page, total, Map.of()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(map(e));
        }
    }

    /**
     * Lexical query shape (package-visible for the 007-6 EXPLAIN guard: the
     * test explains exactly what the engine runs). Single emitted predicate
     * form: {@code @>} containment — the corpus uses single-attribute
     * equalities, so one shape covers all frozen queries; {@code ->>} and
     * OR-forms are out of scope until the engine emits them.
     */
    static String lexicalSql(boolean filtered, int limit) {
        return lexicalSql(filtered, true, limit);
    }

    static String lexicalSql(boolean filtered, boolean generationScoped, int limit) {
        return "SELECT chunk_id, doc_id, text, metadata,"
                + " ts_rank(tsv, to_tsquery('english', ?)) AS score"
                + " FROM chunks"
                + " WHERE tsv @@ to_tsquery('english', ?)"
                + (filtered ? " AND metadata @> ?::jsonb" : "")
                // Generation filter pre-retrieval (007-7a): YDB filters
                // post-fetch (isActiveGeneration); PG restricts in SQL —
                // strictly stronger (never returns fewer for filtering),
                // same visible contract once promotion settles.
                + (generationScoped ? " AND generation_id = ?" : "")
                // Fetch cap mirrors YDB's top-100 leg fetch: generous past
                // topK so minScore filtering post-fetch matches the reference
                // shape; the slice (not this cut) is the verdict. Monotonic
                // int — never user text.
                + " ORDER BY score DESC LIMIT " + Math.max(limit, 100);
    }

    private List<SearchHit> fetchLexical(
            String tenant, SearchRequest request, String tsquery, int limit) throws Exception {
        boolean filtered = !request.filters().mustMatchMetadata().isEmpty();
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var set = conn.createStatement()) {
                set.execute("SET LOCAL app.tenant_id = '" + tenant.replace("'", "''") + "'");
            }
            // YDB mirror: absent pointer (never adopted) means no filter.
            String active = selectPointer(conn, tenant);
            String sql = lexicalSql(filtered, active != null, limit);
            List<SearchHit> matches = new java.util.ArrayList<>();
            try (var ps = conn.prepareStatement(sql)) {
                int param = 1;
                ps.setString(param++, tsquery);
                ps.setString(param++, tsquery);
                if (filtered) {
                    ps.setString(param++, metadataJson(request.filters().mustMatchMetadata()));
                }
                if (active != null) {
                    ps.setString(param++, active);
                }
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double score = rs.getDouble("score");
                        if (score < request.minScore()) {
                            continue;
                        }
                        matches.add(
                                new SearchHit(
                                        ChunkId.of(rs.getString("chunk_id")),
                                        DocumentId.of(rs.getString("doc_id")),
                                        score,
                                        rs.getString("text"),
                                        metadataMap(rs.getString("metadata"))));
                    }
                }
            }
            conn.commit();
            // Post-retrieval tie-break per 013: server order is deterministic
            // but not chunkId-asc — never trust it past the fetch.
            matches.sort(
                    java.util.Comparator.comparingDouble(SearchHit::score)
                            .reversed()
                            .thenComparing(h -> h.chunkId().value()));
            return matches;
        }
    }

    /**
     * Disjunctive tsquery from raw user text. Tokens are lowercased
     * alphanumerics joined with {@code |} — the bound value can only ever
     * contain {@code [a-z0-9 |]}, so server-side tsquery parsing is
     * semantics, not interpolation (YDB-041 parameterization holds: the
     * value travels as a bound parameter, never concatenated SQL).
     */
    static String toDisjunctiveTsquery(String queryText) {
        String[] tokens = queryText.toLowerCase().split("[^a-z0-9]+");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(token);
        }
        return sb.toString();
    }

    private static String metadataJson(java.util.Map<String, String> metadata) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : metadata.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> metadataMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        // Minimal jsonb-object reader: writer stores flat string maps only.
        java.util.Map<String, String> out = new java.util.HashMap<>();
        String body = json.trim();
        if (body.startsWith("{") && body.endsWith("}")) {
            body = body.substring(1, body.length() - 1);
        }
        for (String pair : body.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                out.put(unquote(kv[0].trim()), unquote(kv[1].trim()));
            }
        }
        return out;
    }

    private static String unquote(String quoted) {
        if (quoted.startsWith("\"") && quoted.endsWith("\"") && quoted.length() >= 2) {
            return quoted.substring(1, quoted.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return quoted;
    }

    private static StorageException map(Exception e) {
        if (e instanceof StorageException se) {
            return se;
        }
        return new StorageException(StorageErrorKind.TRANSIENT, "PG search failed: " + e.getMessage());
    }

    /**
     * 007-3 vector: cosine distance ({@code <=>}, production COSINE parity)
     * over the shared {@code embedding} column, RLS-scoped per operation,
     * post-retrieval tie-break per 013. Score stored as {@code -distance} so
     * higher-better ordering (and {@code minScore}) is uniform with lexical.
     * No index forcing — the planner chooses (btree-sort, IVFFlat, HNSW, or
     * an honest seqscan at small scale); the topology test records what it
     * picked per selectivity leg instead of asserting a shape.
     */
    private CompletionStage<SearchResult> searchVector(
            String tenant, SearchRequest request, int fetchLimit) {
        if (request.queryEmbedding().isEmpty()) {
            return CompletableFuture.completedFuture(new SearchResult(List.of(), 0, Map.of()));
        }
        try {
            List<SearchHit> matches = fetchVector(tenant, request, fetchLimit);
            int total = matches.size();
            List<SearchHit> page =
                    matches.subList(0, Math.min(request.topK(), matches.size()));
            return CompletableFuture.completedFuture(new SearchResult(page, total, Map.of()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(map(e));
        }
    }

    /**
     * Vector query shape (package-visible for the 007-6 EXPLAIN guard).
     * Same single {@code @>} predicate form as lexical.
     */
    static String vectorSql(boolean filtered, int limit) {
        return vectorSql(filtered, true, limit);
    }

    static String vectorSql(boolean filtered, boolean generationScoped, int limit) {
        return "SELECT chunk_id, doc_id, text, metadata,"
                + " (embedding <=> ?::vector) AS dist"
                + " FROM chunks"
                + (filtered || generationScoped ? " WHERE " : "")
                + (filtered ? "metadata @> ?::jsonb" : "")
                + (filtered && generationScoped ? " AND " : "")
                + (generationScoped ? "generation_id = ?" : "")
                // Same fetch-cap shape as lexical (YDB top-100 mirror).
                + " ORDER BY dist LIMIT " + Math.max(limit, 100);
    }

    private List<SearchHit> fetchVector(String tenant, SearchRequest request, int fetchLimit)
            throws Exception {
        boolean filtered = !request.filters().mustMatchMetadata().isEmpty();
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var set = conn.createStatement()) {
                set.execute("SET LOCAL app.tenant_id = '" + tenant.replace("'", "''") + "'");
            }
            String active = selectPointer(conn, tenant);
            String sql = vectorSql(filtered, active != null, fetchLimit);
            List<SearchHit> matches = new java.util.ArrayList<>();
            try (var ps = conn.prepareStatement(sql)) {
                int param = 1;
                ps.setString(param++, paddedVector(request.queryEmbedding().get()));
                if (filtered) {
                    ps.setString(param++, metadataJson(request.filters().mustMatchMetadata()));
                }
                if (active != null) {
                    ps.setString(param++, active);
                }
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double score = -rs.getDouble("dist");
                        if (rs.wasNull() || score < request.minScore()) {
                            continue;
                        }
                        matches.add(
                                new SearchHit(
                                        ChunkId.of(rs.getString("chunk_id")),
                                        DocumentId.of(rs.getString("doc_id")),
                                        score,
                                        rs.getString("text"),
                                        metadataMap(rs.getString("metadata"))));
                    }
                }
            }
            conn.commit();
            matches.sort(
                    java.util.Comparator.comparingDouble(SearchHit::score)
                            .reversed()
                            .thenComparing(h -> h.chunkId().value()));
            return matches;
        }
    }

    /**
     * 007-5 hybrid: rank-based RRF mirroring YDB shape exactly (probe-bound):
     * top-100 inputs per leg, {@code 1/(60+rank+1)} per list, hybrid score
     * {@code rrf/maxLexicalScore}, same minScore application points
     * (per-leg raw, hybrid normalized — non-portable values, probe caveat).
     * Generation filtering is absent (lands in 007-7); highlights are empty
     * (side channels land in 008). Confirmed: top-100 inputs, not top-K —
     * fusion compares the same candidate-set shape as YDB.
     */
    private CompletionStage<SearchResult> searchHybrid(String tenant, SearchRequest request) {
        final int legInput = 100;
        try {
            String tsquery = toDisjunctiveTsquery(request.queryText());
            List<SearchHit> lexical =
                    tsquery.isEmpty()
                            ? List.of()
                            : fetchLexical(tenant, request, tsquery, legInput);
            List<SearchHit> dense =
                    request.queryEmbedding().isEmpty()
                            ? List.of()
                            : fetchVector(tenant, request, legInput);
            java.util.Map<String, double[]> acc = new java.util.LinkedHashMap<>();
            java.util.Map<String, SearchHit> byId = new java.util.LinkedHashMap<>();
            rankInto(acc, byId, lexical);
            rankInto(acc, byId, dense);
            double maxLex = 1e-9;
            for (SearchHit hit : lexical) {
                maxLex = Math.max(maxLex, hit.score());
            }
            List<SearchHit> fused = new java.util.ArrayList<>();
            for (var entry : acc.entrySet()) {
                double score = entry.getValue()[0] / maxLex;
                if (score < request.minScore()) {
                    continue;
                }
                SearchHit base = byId.get(entry.getKey());
                fused.add(
                        new SearchHit(
                                base.chunkId(), base.documentId(), score, base.text(), base.metadata()));
            }
            fused.sort(
                    java.util.Comparator.comparingDouble(SearchHit::score)
                            .reversed()
                            .thenComparing(h -> h.chunkId().value()));
            int total = fused.size();
            List<SearchHit> page = fused.subList(0, Math.min(request.topK(), fused.size()));
            return CompletableFuture.completedFuture(new SearchResult(page, total, Map.of()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(map(e));
        }
    }

    private static void rankInto(
            java.util.Map<String, double[]> acc,
            java.util.Map<String, SearchHit> byId,
            List<SearchHit> leg) {
        // Probe-bound k=60, YDB formula verbatim: 1/(60+rank+1) per list.
        for (int rank = 0; rank < leg.size(); rank++) {
            SearchHit hit = leg.get(rank);
            double[] slot = acc.computeIfAbsent(hit.chunkId().value(), k -> new double[1]);
            slot[0] += 1.0 / (60 + rank + 1);
            byId.putIfAbsent(hit.chunkId().value(), hit);
        }
    }

    /**
     * Query embedding padded to the fixed 384 columns (same rule as writer
     * storage: sub-384 zero-pads, over-384 rejects). Shared so write and
     * read agree on what a toy vector means.
     */
    static String paddedVector(float[] embedding) {
        return vectorLiteral(embedding);
    }

    @Override
    public SearchCapabilities capabilities() {
        // §9.3: unverified flags report false. Lexical (007-2), vector
        // (007-3), hybrid (007-5) and filters (007-6) flipped on evidence;
        // the rest flip with their own tasks.
        return new SearchCapabilities(true, true, true, true, false, false, false, false);
    }

    // ---- writer ----

    /**
     * Projection writes, grouped per tenant (one transaction each —
     * {@code SET LOCAL} scopes per transaction). Generation gate first
     * (stale batch fails the tenant tx atomically), then ordering-guarded
     * row writes. See {@code adoptOrCheck} for the pointer contract.
     */
    @Override
    public CompletionStage<Void> upsert(List<ChunkProjection> projections) {
        try {
            java.util.Map<String, List<ChunkProjection>> byTenant = new java.util.LinkedHashMap<>();
            for (ChunkProjection p : projections) {
                byTenant.computeIfAbsent(p.tenantId(), t -> new java.util.ArrayList<>()).add(p);
            }
            for (var entry : byTenant.entrySet()) {
                upsertTenant(entry.getKey(), entry.getValue());
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(map(e));
        }
    }

    /**
     * Generation pointer, YDB P0-1 equivalent shape (007-7a). YDB holds the
     * active generation in engine memory (first-write-wins adopt, rebuild
     * flips via map put); PG holds one row per tenant in
     * {@code quest_generations} — a single-row upsert is the atomic flip,
     * and DB state survives restarts and shares across instances. Same
     * contract either way: a query never observes two generations.
     * Deliberate difference (stated, not drifted): YDB's pointer is global
     * ({@code "*"}); PG's is per-tenant — contract-indistinguishable on
     * single-tenant tests, strictly more isolated under multi-tenancy
     * (one tenant's rebuild never flips another's reads).
     */
    private static String selectPointer(java.sql.Connection conn, String tenant) throws Exception {
        try (var ps = conn.prepareStatement(
                "SELECT active_generation FROM quest_generations WHERE tenant_id = ?")) {
            ps.setString(1, tenant);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String adoptOrCheck(
            java.sql.Connection conn, String tenant, GenerationId generation) throws Exception {
        String active = selectPointer(conn, tenant);
        if (active == null) {
            try (var ps = conn.prepareStatement(
                    "INSERT INTO quest_generations (tenant_id, active_generation)"
                            + " VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, tenant);
                ps.setString(2, generation.value());
                ps.executeUpdate();
            }
            active = selectPointer(conn, tenant);
        }
        if (active != null && !active.equals(generation.value())) {
            throw new StorageException(
                    StorageErrorKind.CONFLICT,
                    "CONFLICT: stale generation '" + generation.value()
                            + "', active is '" + active + "'");
        }
        return active;
    }

    private static Long storedOrdering(
            java.sql.Connection conn, String tenant, String chunkId) throws Exception {
        try (var ps = conn.prepareStatement(
                "SELECT ordering_key FROM chunks WHERE tenant_id = ? AND chunk_id = ?")) {
            ps.setString(1, tenant);
            ps.setString(2, chunkId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    long value = rs.getLong(1);
                    return rs.wasNull() ? null : value;
                }
                return null;
            }
        }
    }

    private void upsertTenant(String tenant, List<ChunkProjection> projections) throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var set = conn.createStatement()) {
                set.execute("SET LOCAL app.tenant_id = '" + tenant.replace("'", "''") + "'");
            }
            // Generation gate first: adopt once per tenant-batch, then
            // compare without further round-trips (160k-row loads stay
            // linear). A stale batch fails the whole tenant transaction
            // (atomic — strictly stronger than YDB's prefix-apply-then-fail;
            // the contract asserts only the CONFLICT).
            String active = adoptOrCheck(conn, tenant, projections.get(0).generationId());
            for (ChunkProjection p : projections) {
                if (!p.generationId().value().equals(active)) {
                    throw new StorageException(
                            StorageErrorKind.CONFLICT,
                            "CONFLICT: stale generation '" + p.generationId().value()
                                    + "', active is '" + active + "'");
                }
            }
            try (var ps =
                    conn.prepareStatement(
                            "INSERT INTO chunks (tenant_id, chunk_id, doc_id, ordinal, text,"
                                    + " token_count, metadata, embedding, tsv, generation_id,"
                                    + " ordering_key)"
                                    + " VALUES (?, ?, ?, 0, ?, ?, ?::jsonb, ?::vector,"
                                    + " to_tsvector('english', ?), ?, ?)"
                                    + " ON CONFLICT (tenant_id, chunk_id) DO UPDATE SET"
                                    + " doc_id = EXCLUDED.doc_id, text = EXCLUDED.text,"
                                    + " token_count = EXCLUDED.token_count,"
                                    + " metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding,"
                                    + " tsv = EXCLUDED.tsv, generation_id = EXCLUDED.generation_id,"
                                    + " ordering_key = EXCLUDED.ordering_key")) {
                for (ChunkProjection p : projections) {
                    // Ordering guard (041.3 discipline): strict >, equal-key
                    // drops, log-not-fail — retry-safe by construction. YDB
                    // silently continues; PG logs the drop (superset, the
                    // retry-safety argument applies identically).
                    Long stored = storedOrdering(conn, tenant, p.chunkId().value());
                    if (stored != null && p.orderingKey() <= stored) {
                        LOG.info(() ->
                                "ordering-guard drop: tenant=" + tenant
                                        + " chunk=" + p.chunkId().value()
                                        + " incoming=" + p.orderingKey()
                                        + " stored=" + stored);
                        continue;
                    }
                    ps.setString(1, tenant);
                    ps.setString(2, p.chunkId().value());
                    ps.setString(3, p.documentId().value());
                    ps.setString(4, p.text());
                    ps.setInt(5, tokenCount(p.text()));
                    ps.setString(6, metadataJson(p.metadata()));
                    if (p.embedding() == null) {
                        ps.setNull(7, java.sql.Types.OTHER);
                    } else {
                        ps.setString(7, vectorLiteral(p.embedding()));
                    }
                    ps.setString(8, p.text());
                    ps.setString(9, p.generationId().value());
                    ps.setLong(10, p.orderingKey());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    private static int tokenCount(String text) {
        String stripped = text.trim();
        if (stripped.isEmpty()) {
            return 0;
        }
        return stripped.split("\\s+").length;
    }

    /**
     * Vector literal for the fixed {@code vector(384)} column (dimension
     * note, ticket). Toy vectors shorter than 384 pad with zeros — orthogonal
     * stays orthogonal, so contract discrimination intent survives; longer
     * than 384 is rejected, never truncated. Null is handled by the caller
     * ({@code setNull}), never padded into a zero vector.
     */
    private static String vectorLiteral(float[] embedding) {
        if (embedding.length > 384) {
            throw new IllegalArgumentException(
                    "embedding has " + embedding.length + " dims, column is vector(384)");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 384; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(i < embedding.length ? embedding[i] : 0.0f);
        }
        return sb.append(']').toString();
    }

    @Override
    public CompletionStage<Void> delete(GenerationId generationId, Collection<ChunkId> ids) {
        // Generation-scoped delete (YDB mirror: only rows at the requested
        // generation go). Tenant discovery via the DEFINER lookup — the port
        // supplies no tenant, and YDB scans its local indexes for the same
        // reason. Each tenant's delete runs scoped (RLS still binds it).
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            for (ChunkId id : ids) {
                List<String> tenants = new java.util.ArrayList<>();
                try (var ps = conn.prepareStatement(
                        "SELECT tenant_id FROM quest_chunk_tenants(?, ?)")) {
                    ps.setString(1, id.value());
                    ps.setString(2, generationId.value());
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            tenants.add(rs.getString(1));
                        }
                    }
                }
                for (String tenant : tenants) {
                    try (var set = conn.createStatement()) {
                        set.execute("SET LOCAL app.tenant_id = '" + tenant.replace("'", "''") + "'");
                    }
                    try (var ps = conn.prepareStatement(
                            "DELETE FROM chunks WHERE tenant_id = ? AND chunk_id = ?"
                                    + " AND generation_id = ?")) {
                        ps.setString(1, tenant);
                        ps.setString(2, id.value());
                        ps.setString(3, generationId.value());
                        ps.executeUpdate();
                    }
                }
            }
            conn.commit();
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(map(e));
        }
    }

    // ---- admin ----

    @Override
    public CompletionStage<Void> ensureSchema(SchemaOptions options) {
        return CompletableFuture.failedFuture(todo("ensureSchema", "007-1 follow-up"));
    }

    @Override
    public CompletionStage<Void> rebuild(RebuildOptions options) {
        // Promotion flip (YDB P0-1 mirror): non-full flips the pointer only
        // (rows remain — the promotion-window test's exact setup); full
        // clears quest rows first. Both via DEFINER (port gives no tenant;
        // the flip is global like YDB "*"). Single statements — the flip is
        // the atomic promotion edge the no-mixed-generations test needs.
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var stmt = conn.createStatement()) {
                if (options.full()) {
                    stmt.execute("SELECT quest_reset_quest_rows()");
                }
                try (var ps = conn.prepareStatement("SELECT quest_promote_flip(?)")) {
                    ps.setString(1, options.targetGeneration().value());
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
            }
            conn.commit();
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(map(e));
        }
    }

    @Override
    public CompletionStage<IndexStatus> status() {
        return CompletableFuture.failedFuture(todo("status", "007-1 follow-up"));
    }

    // ---- Conformant ----

    @Override
    public String adapterName() {
        return "postgres";
    }

    @Override
    public String adapterVersion() {
        return "1.0.0";
    }

    @Override
    public ConformanceMatrix conformance() {
        String evidence = "org.synanton.synquest.postgres.PostgresSynquestEngineTest";
        return new ConformanceMatrix(
                adapterName(),
                adapterVersion(),
                List.of(
                        ConformanceEntry.supported(Capabilities.SYNQUEST_LEXICAL, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_VECTOR, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_HYBRID, evidence),
                        ConformanceEntry.supported(Capabilities.SYNQUEST_FILTERS, evidence),
                        // Tenant scope enforced pre-ranking (RLS + generation
                        // filter in SQL); principal/policy dimensions are
                        // 025b territory — partial, honestly scoped.
                        ConformanceEntry.partial(
                                Capabilities.SYNQUEST_ELIGIBILITY, "tenant", evidence),
                        ConformanceEntry.supported(
                                Capabilities.SYNQUEST_TEMPORAL_REJECTION, evidence)));
    }
}
