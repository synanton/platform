package org.synanton.synquest.postgres;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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
            return searchLexical(tenant, request);
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
    private CompletionStage<SearchResult> searchLexical(String tenant, SearchRequest request) {
        String tsquery = toDisjunctiveTsquery(request.queryText());
        if (tsquery.isEmpty()) {
            return CompletableFuture.completedFuture(new SearchResult(List.of(), 0, Map.of()));
        }
        boolean filtered = !request.filters().mustMatchMetadata().isEmpty();
        String sql =
                "SELECT chunk_id, doc_id, text, metadata,"
                        + " ts_rank(tsv, to_tsquery('english', ?)) AS score"
                        + " FROM chunks"
                        + " WHERE tsv @@ to_tsquery('english', ?)"
                        + (filtered ? " AND metadata @> ?::jsonb" : "");
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var set = conn.createStatement()) {
                set.execute("SET LOCAL app.tenant_id = '" + tenant.replace("'", "''") + "'");
            }
            List<SearchHit> matches = new java.util.ArrayList<>();
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, tsquery);
                ps.setString(2, tsquery);
                if (filtered) {
                    ps.setString(3, metadataJson(request.filters().mustMatchMetadata()));
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
            int total = matches.size();
            List<SearchHit> page = matches.subList(0, Math.min(request.topK(), matches.size()));
            return CompletableFuture.completedFuture(new SearchResult(page, total, Map.of()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(map(e));
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

    @Override
    public SearchCapabilities capabilities() {
        // §9.3: unverified flags report false. Lexical flipped with 007-2
        // evidence; each remaining flag flips with its own task.
        return new SearchCapabilities(true, false, false, false, false, false, false, false);
    }

    // ---- writer ----

    /**
     * 007-2 writer (minimal): projection rows into the shared {@code chunks}
     * table with maintained {@code tsv} + stored generation/ordering values.
     * Plain overwrite on conflict — ordering-guard (regression prevention)
     * and generation promotion semantics land in 007-5/007-7; the
     * corresponding contract tests stay red until then. Grouped per tenant:
     * {@code SET LOCAL} scopes to one transaction, so multi-tenant batches
     * commit tenant by tenant.
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

    private void upsertTenant(String tenant, List<ChunkProjection> projections) throws Exception {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var set = conn.createStatement()) {
                set.execute("SET LOCAL app.tenant_id = '" + tenant.replace("'", "''") + "'");
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
        return CompletableFuture.failedFuture(todo("delete", "007-5"));
    }

    // ---- admin ----

    @Override
    public CompletionStage<Void> ensureSchema(SchemaOptions options) {
        return CompletableFuture.failedFuture(todo("ensureSchema", "007-1 follow-up"));
    }

    @Override
    public CompletionStage<Void> rebuild(RebuildOptions options) {
        return CompletableFuture.failedFuture(todo("rebuild", "007-5"));
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
                        ConformanceEntry.unverified(
                                Capabilities.SYNQUEST_VECTOR, "007-1 scaffold, lands in 007-3"),
                        ConformanceEntry.unverified(
                                Capabilities.SYNQUEST_HYBRID, "007-1 scaffold, lands in 007-5"),
                        ConformanceEntry.unverified(
                                Capabilities.SYNQUEST_FILTERS,
                                "007-1 scaffold, lands in 007-6")));
    }
}
