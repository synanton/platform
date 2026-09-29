package org.synanton.synvault.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.sql.DataSource;
import org.synanton.storage.contract.AdapterMetrics;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.contract.ConformanceEntry;
import org.synanton.storage.contract.ConformanceMatrix;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.InMemoryAdapterMetrics;
import org.synanton.storage.contract.PageRequest;
import org.synanton.storage.contract.Provisional;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.SourceVersionId;
import org.synanton.storage.contract.StorageErrorKind;
import org.synanton.storage.contract.StorageException;
import org.synanton.synvault.api.Chunk;
import org.synanton.synvault.api.ChunkPage;
import org.synanton.synvault.api.ChunkQuery;
import org.synanton.synvault.api.ConsistencyLevel;
import org.synanton.synvault.api.Document;
import org.synanton.synvault.api.DocumentRevision;
import org.synanton.synvault.api.DocumentWriteOptions;
import org.synanton.synvault.api.ProvenanceRecord;
import org.synanton.synvault.api.PublicationIntent;
import org.synanton.synvault.api.RevisionWriteOptions;
import org.synanton.synvault.api.StoreCapabilities;
import org.synanton.synvault.api.SynvaultStore;

/**
 * PostgreSQL {@link SynvaultStore} adapter (PG-POC-004).
 *
 * <p>Single-node ACID replaces YDB's distributed transactions: the revision
 * commit (document + chunks + provenance + publication) is one JDBC
 * transaction. Pre-ranking eligibility is enforced by row-level security —
 * every transaction opens with {@code SET LOCAL app.tenant_id}, so the
 * policy path is exercised on every read and write (RLS is a mechanism, not
 * the authorization source; the validated {@code SecurityContext} governs).
 *
 * <p>Identifier compatibility (PG-POC-004 finding): domain ids are arbitrary
 * strings ({@code tenant_a}, {@code d1}), so the schema uses {@code text}
 * PKs (uuid columns cannot round-trip them). RLS compares as text.
 */
public class PostgresSynvaultStore implements SynvaultStore, Conformant {

    private final DataSource dataSource;
    private final AdapterMetrics metrics;

    /** Test-only failure hook: throws after chunk writes inside the revision tx. */
    public volatile boolean failAfterChunks;

    public PostgresSynvaultStore(DataSource dataSource) {
        this(dataSource, new InMemoryAdapterMetrics("postgres@1.0.0"));
    }

    public PostgresSynvaultStore(DataSource dataSource, AdapterMetrics metrics) {
        this.dataSource = dataSource;
        this.metrics = metrics;
    }

    // ---- SynvaultStore ----

    @Override
    public CompletionStage<Document> putDocument(
            SecurityContext context, Document document, DocumentWriteOptions options) {
        long start = System.nanoTime();
        String op = AdapterMetrics.SYNVAULT_PUT;
        // Metadata-only: never touches chunks, provenance, or publication.
        String sql =
                "SELECT storage_revision, created_at FROM documents"
                        + " WHERE tenant_id = ? AND doc_id = ?";
        String upsert =
                "INSERT INTO documents (tenant_id, doc_id, title, source_uri, metadata,"
                        + " storage_revision, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)"
                        + " ON CONFLICT (tenant_id, doc_id) DO UPDATE SET"
                        + " title = EXCLUDED.title, source_uri = EXCLUDED.source_uri,"
                        + " metadata = EXCLUDED.metadata, updated_at = EXCLUDED.updated_at";
        try (Connection conn = tx(context)) {
            String tenant = context.tenantScope().tenantId();
            String doc = document.id().value();
            long revision = 0L;
            Instant created = Instant.now();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, tenant);
                ps.setObject(2, doc);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        revision = rs.getLong(1);
                        created = rs.getTimestamp(2).toInstant();
                    }
                }
            }
            Instant now = Instant.now();
            try (PreparedStatement ps = conn.prepareStatement(upsert)) {
                ps.setObject(1, tenant);
                ps.setObject(2, doc);
                ps.setString(3, document.title());
                ps.setString(4, document.sourceUri());
                ps.setString(5, toJson(document.metadata()));
                ps.setLong(6, revision);
                ps.setTimestamp(7, Timestamp.from(created));
                ps.setTimestamp(8, Timestamp.from(now));
                ps.executeUpdate();
            }
            conn.commit();
            Document stored =
                    new Document(
                            document.id(), document.title(), document.sourceUri(),
                            document.metadata(), revision, created, now);
            metrics.record(op, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(stored);
        } catch (SQLException e) {
            metrics.record(op, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public CompletionStage<Optional<Document>> getDocument(SecurityContext context, DocumentId id) {
        long start = System.nanoTime();
        String sql =
                "SELECT title, source_uri, metadata, storage_revision, created_at, updated_at"
                        + " FROM documents WHERE tenant_id = ? AND doc_id = ?";
        try (Connection conn = tx(context)) {
            Optional<Document> found;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, context.tenantScope().tenantId());
                ps.setString(2, id.value());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        found =
                                Optional.of(
                                        new Document(
                                                id,
                                                rs.getString(1),
                                                rs.getString(2),
                                                fromJson(rs.getString(3)),
                                                rs.getLong(4),
                                                rs.getTimestamp(5).toInstant(),
                                                rs.getTimestamp(6).toInstant()));
                    } else {
                        found = Optional.empty();
                    }
                }
            }
            conn.commit();
            metrics.record(AdapterMetrics.SYNVAULT_GET, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(found);
        } catch (SQLException e) {
            metrics.record(AdapterMetrics.SYNVAULT_GET, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public CompletionStage<Void> deleteDocument(SecurityContext context, DocumentId id) {
        long start = System.nanoTime();
        String op = AdapterMetrics.SYNVAULT_DELETE;
        try (Connection conn = tx(context)) {
            String tenant = context.tenantScope().tenantId();
            String doc = id.value();
            // Provenance + chunks + document in one transaction (same guarantee
            // shape as the revision commit, inverse direction). Provenance rows
            // join through chunks since they carry no doc_id of their own.
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "DELETE FROM provenance USING chunks"
                                    + " WHERE provenance.tenant_id = chunks.tenant_id"
                                    + " AND provenance.chunk_id = chunks.chunk_id"
                                    + " AND chunks.tenant_id = ? AND chunks.doc_id = ?")) {
                ps.setObject(1, tenant);
                ps.setObject(2, doc);
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "DELETE FROM chunks WHERE tenant_id = ? AND doc_id = ?")) {
                ps.setObject(1, tenant);
                ps.setObject(2, doc);
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "DELETE FROM documents WHERE tenant_id = ? AND doc_id = ?")) {
                ps.setObject(1, tenant);
                ps.setObject(2, doc);
                ps.executeUpdate();
            }
            conn.commit();
            metrics.record(op, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(null);
        } catch (SQLException e) {
            metrics.record(op, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public CompletionStage<ChunkPage> getChunks(
            SecurityContext context, DocumentId documentId, ChunkQuery query, PageRequest page) {
        long start = System.nanoTime();
        int from = page.cursor().map(cursor -> Integer.parseInt(cursor) + 1).orElse(0);
        // Metadata filters push into SQL (jsonb containment, GIN-backed) so
        // pagination operates on filtered rows — LIMIT-before-filter would
        // under-fill pages. Keys travel as bound parameters, never text.
        StringBuilder sql = new StringBuilder(
                "SELECT chunk_id, ordinal, text, token_count, metadata, embedding"
                        + " FROM chunks WHERE tenant_id = ? AND doc_id = ? AND ordinal >= ?");
        List<String> filterValues = new ArrayList<>(query.mustMatchMetadata().values());
        for (int i = 0; i < query.mustMatchMetadata().size(); i++) {
            sql.append(" AND metadata @> ?::jsonb");
        }
        sql.append(" ORDER BY ordinal LIMIT ?");
        try (Connection conn = tx(context)) {
            List<Chunk> window = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int p = 1;
                ps.setString(p++, context.tenantScope().tenantId());
                ps.setString(p++, documentId.value());
                ps.setInt(p++, from);
                int keyIndex = 0;
                for (String key : query.mustMatchMetadata().keySet()) {
                    ps.setString(p++, toJson(Map.of(key, filterValues.get(keyIndex++))));
                }
                ps.setInt(p, page.limit() + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        window.add(
                                new Chunk(
                                        ChunkId.of(rs.getString(1)),
                                        documentId,
                                        rs.getInt(2),
                                        rs.getString(3),
                                        rs.getInt(4),
                                        fromJson(rs.getString(5)),
                                        decodeEmbedding(rs.getString(6))));
                    }
                }
            }
            // Domain ids round-trip as stored (text PKs, no mapping).
            List<Chunk> items =
                    window.stream()
                            .sorted(Comparator.comparingInt(Chunk::ordinal))
                            .limit(page.limit())
                            .toList();
            Optional<String> nextCursor =
                    window.size() > items.size()
                            ? Optional.of(String.valueOf(items.get(items.size() - 1).ordinal()))
                            : Optional.empty();
            conn.commit();
            metrics.record(AdapterMetrics.SYNVAULT_CHUNKS, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(new ChunkPage(items, nextCursor));
        } catch (SQLException e) {
            metrics.record(AdapterMetrics.SYNVAULT_CHUNKS, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public CompletionStage<Void> putDocumentRevision(
            SecurityContext context, DocumentRevision revision, RevisionWriteOptions options) {
        long start = System.nanoTime();
        String op = AdapterMetrics.SYNVAULT_REVISION;
        DocumentId docId = revision.document().id();
        try (Connection conn = tx(context)) {
            String tenant = context.tenantScope().tenantId();
            String doc = docId.value();
            long stored = 0L;
            Instant created = Instant.now();
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "SELECT storage_revision, created_at FROM documents"
                                    + " WHERE tenant_id = ? AND doc_id = ? FOR UPDATE")) {
                ps.setObject(1, tenant);
                ps.setObject(2, doc);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        stored = rs.getLong(1);
                        created = rs.getTimestamp(2).toInstant();
                    }
                }
            }
            if (options.expectedRevision().isPresent()
                    && options.expectedRevision().get() != stored) {
                conn.rollback();
                metrics.record(op, System.nanoTime() - start, false);
                throw new StorageException(
                        StorageErrorKind.CONFLICT,
                        "CONFLICT: expected revision " + options.expectedRevision().get()
                                + " but stored is " + stored);
            }
            long committed = stored + 1;
            Instant now = Instant.now();
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO documents (tenant_id, doc_id, title, source_uri,"
                                    + " metadata, storage_revision, created_at, updated_at)"
                                    + " VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)"
                                    + " ON CONFLICT (tenant_id, doc_id) DO UPDATE SET"
                                    + " title = EXCLUDED.title, source_uri = EXCLUDED.source_uri,"
                                    + " metadata = EXCLUDED.metadata,"
                                    + " storage_revision = EXCLUDED.storage_revision,"
                                    + " updated_at = EXCLUDED.updated_at")) {
                ps.setObject(1, tenant);
                ps.setObject(2, doc);
                ps.setString(3, revision.document().title());
                ps.setString(4, revision.document().sourceUri());
                ps.setString(5, toJson(revision.document().metadata()));
                ps.setLong(6, committed);
                ps.setTimestamp(7, Timestamp.from(created));
                ps.setTimestamp(8, Timestamp.from(now));
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "DELETE FROM provenance USING chunks"
                                    + " WHERE provenance.tenant_id = chunks.tenant_id"
                                    + " AND provenance.chunk_id = chunks.chunk_id"
                                    + " AND chunks.tenant_id = ? AND chunks.doc_id = ?")) {
                ps.setObject(1, tenant);
                ps.setObject(2, doc);
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "DELETE FROM chunks WHERE tenant_id = ? AND doc_id = ?")) {
                ps.setObject(1, tenant);
                ps.setObject(2, doc);
                ps.executeUpdate();
            }
            for (Chunk chunk : revision.chunks()) {
                try (PreparedStatement ps =
                        conn.prepareStatement(
                                "INSERT INTO chunks (tenant_id, chunk_id, doc_id, ordinal,"
                                        + " text, token_count, metadata, embedding)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)")) {
                    ps.setObject(1, tenant);
                    ps.setString(2, chunk.id().value());
                    ps.setObject(3, doc);
                    ps.setInt(4, chunk.ordinal());
                    ps.setString(5, chunk.text());
                    ps.setInt(6, chunk.tokenCount());
                    ps.setString(7, toJson(chunk.metadata()));
                    if (chunk.embedding() == null) {
                        ps.setNull(8, java.sql.Types.OTHER);
                    } else {
                        ps.setString(8, encodeEmbedding(chunk.embedding()));
                    }
                    ps.executeUpdate();
                }
            }
            if (failAfterChunks) {
                throw new IllegalStateException("injected failure after chunk writes");
            }
            for (ProvenanceRecord record : revision.provenance()) {
                try (PreparedStatement ps =
                        conn.prepareStatement(
                                "INSERT INTO provenance (tenant_id, chunk_id, extractor,"
                                        + " source_version_id, embedding_model_ref, page,"
                                        + " start_offset, end_offset)"
                                        + " VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)")) {
                    ps.setObject(1, tenant);
                    ps.setString(2, record.chunkId().value());
                    ps.setString(3, record.extractor());
                    ps.setString(4, record.sourceVersionId().value());
                    ps.setString(5, toJson(modelRef(record.embeddingModelRef())));
                    ps.setInt(6, record.page());
                    ps.setInt(7, record.startOffset());
                    ps.setInt(8, record.endOffset());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT INTO publication_log (tenant_id, revision_id, payload,"
                                    + " created_at, published_at)"
                                    + " VALUES (?, ?, ?::jsonb, ?, NULL)")) {
                ps.setObject(1, tenant);
                ps.setString(2, revision.publication().revisionId());
                ps.setString(3, revision.publication().payloadJson());
                ps.setTimestamp(4, Timestamp.from(now));
                ps.executeUpdate();
            }
            conn.commit();
            metrics.record(op, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(null);
        } catch (SQLException e) {
            metrics.record(op, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    /**
     * Relay seam for the projection consumer (same shape as the YDB adapter's):
     * publication records committed but not yet handed to eventing,
     * tenant-scoped — a relay must never scan across tenants. Oldest first.
     *
     * <p>{@link Provisional} {@code 1.27}: transitional adapter API, not part
     * of {@code SynvaultStore}; tracked for cleanup when the 1.27 client lands.
     */
    @Provisional(value = "1.27", reason = "Relay seam; folds into the 1.27 client contract")
    public CompletionStage<List<PublicationIntent>> pendingPublications(
            SecurityContext context, int limit) {
        String sql =
                "SELECT revision_id, payload, created_at FROM publication_log"
                        + " WHERE tenant_id = ? AND published_at IS NULL"
                        + " ORDER BY created_at LIMIT ?";
        try (Connection conn = tx(context)) {
            List<PublicationIntent> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, context.tenantScope().tenantId());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(
                                new PublicationIntent(
                                        rs.getString(1), context.tenantScope().tenantId(),
                                        rs.getString(2)));
                    }
                }
            }
            conn.commit();
            return CompletableFuture.completedFuture(out);
        } catch (SQLException e) {
            throw map(e);
        }
    }

    /**
     * Marks a publication record handed to eventing (sets {@code published_at}).
     * Idempotent: re-marking an already-published record matches nothing.
     *
     * <p>{@link Provisional} {@code 1.27}: see {@link #pendingPublications}.
     */
    @Provisional(value = "1.27", reason = "Relay seam; folds into the 1.27 client contract")
    public CompletionStage<Void> markPublished(SecurityContext context, String revisionId) {
        String sql =
                "UPDATE publication_log SET published_at = now()"
                        + " WHERE tenant_id = ? AND revision_id = ?";
        try (Connection conn = tx(context)) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, context.tenantScope().tenantId());
                ps.setString(2, revisionId);
                ps.executeUpdate();
            }
            conn.commit();
            return CompletableFuture.completedFuture(null);
        } catch (SQLException e) {
            throw map(e);
        }
    }

    @Override
    public CompletionStage<List<ProvenanceRecord>> getProvenance(
            SecurityContext context, DocumentId id) {
        long start = System.nanoTime();
        // Provenance rows carry no doc_id: join through chunks on
        // (tenant, chunk). RLS applies per table inside the same session.
        String sql =
                "SELECT p.chunk_id, p.extractor, p.source_version_id,"
                        + " p.embedding_model_ref, p.page, p.start_offset, p.end_offset"
                        + " FROM provenance p JOIN chunks c"
                        + " ON p.tenant_id = c.tenant_id AND p.chunk_id = c.chunk_id"
                        + " WHERE c.tenant_id = ? AND c.doc_id = ?";
        try (Connection conn = tx(context)) {
            List<ProvenanceRecord> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, context.tenantScope().tenantId());
                ps.setString(2, id.value());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(
                                new ProvenanceRecord(
                                        ChunkId.of(rs.getString(1)),
                                        rs.getString(2),
                                        SourceVersionId.of(rs.getString(3)),
                                        Optional.empty(),
                                        rs.getInt(5),
                                        rs.getInt(6),
                                        rs.getInt(7)));
                    }
                }
            }
            conn.commit();
            metrics.record(AdapterMetrics.SYNVAULT_PROVENANCE, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(out);
        } catch (SQLException e) {
            metrics.record(AdapterMetrics.SYNVAULT_PROVENANCE, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public StoreCapabilities capabilities() {
        return new StoreCapabilities(true, ConsistencyLevel.STRONG, true, true, true, true);
    }

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
        String evidence = "org.synanton.synvault.postgres.PostgresSynvaultStoreTest";
        return new ConformanceMatrix(
                adapterName(),
                adapterVersion(),
                List.of(
                        ConformanceEntry.supported(Capabilities.SYNVAULT_REVISION, evidence),
                        ConformanceEntry.supported(Capabilities.SYNVAULT_DELETE, evidence),
                        ConformanceEntry.supported(Capabilities.SYNVAULT_DOCUMENT, evidence),
                        ConformanceEntry.supported(Capabilities.SYNVAULT_CHUNKS, evidence),
                        ConformanceEntry.supported(Capabilities.SYNVAULT_PROVENANCE, evidence),
                        ConformanceEntry.supported(Capabilities.SYNVAULT_PAGINATION, evidence),
                        ConformanceEntry.supported(Capabilities.SYNVAULT_OCC, evidence)));
    }

    // ---- internals ----

    /**
     * Opens a transaction with the validated tenant bound into RLS.
     * {@code SET LOCAL} scopes to the transaction (never session state, so
     * pooled-connection reuse cannot leak a tenant) and reverts on
     * commit/rollback. Callers must commit or roll back (try-with-resources
     * closes without commit on the happy path only if callers commit first —
     * every method above commits explicitly; failures roll back via close
     * only after an explicit rollback attempt... see below).
     */
    private Connection tx(SecurityContext context) throws SQLException {
        Connection conn = dataSource.getConnection();
        boolean ok = false;
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps =
                    conn.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, context.tenantScope().tenantId());
                ps.executeQuery();
            }
            ok = true;
            return conn;
        } finally {
            if (!ok) {
                conn.close();
            }
        }
    }

    private static String toJson(Map<String, String> metadata) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append("\"");
        }
        return sb.append("}").toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, String> fromJson(String json) {
        // Minimal jsonb {"k":"v"} reader: matches toJson's output shape only.
        Map<String, String> out = new LinkedHashMap<>();
        String body = json.trim();
        if (body.length() <= 2) {
            return out;
        }
        for (String pair : body.substring(1, body.length() - 1).split("\",\"")) {
            String[] kv = pair.replaceFirst("^\"", "").split("\":\"", 2);
            if (kv.length == 2) {
                out.put(unescape(kv[0]), unescape(kv[1].replaceFirst("\"$", "")));
            }
        }
        return out;
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static Map<String, String> modelRef(Optional<EmbeddingModelRef> ref) {
        return ref.map(
                        m ->
                                Map.of(
                                        "id", m.modelId(),
                                        "version", m.version(),
                                        "digest", m.digest()))
                .orElse(Map.of());
    }

    private static String encodeEmbedding(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(embedding[i]);
        }
        return sb.append("]").toString();
    }

    private static float[] decodeEmbedding(String literal) {
        if (literal == null) {
            return null;
        }
        String body = literal.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i].trim());
        }
        return out;
    }

    private static RuntimeException map(SQLException e) {
        // 23505 unique violation, 23503 foreign key: contract conflicts.
        if ("23505".equals(e.getSQLState()) || "23503".equals(e.getSQLState())) {
            return new StorageException(StorageErrorKind.CONFLICT, "CONFLICT: " + e.getMessage(), e);
        }
        return new StorageException(StorageErrorKind.TRANSIENT, "TRANSIENT: " + e.getMessage(), e);
    }
}
