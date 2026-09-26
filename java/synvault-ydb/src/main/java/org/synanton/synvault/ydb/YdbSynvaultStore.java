package org.synanton.synvault.ydb;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
 * YDB {@link SynvaultStore} adapter (021) — the first adapter implementing the full
 * {@code DocumentRevision} atomicity contract: document + chunks + provenance +
 * publication commit atomically in one {@code SERIALIZABLE_READ_WRITE} transaction.
 *
 * <p>Schema deviations from §11.1 (all recorded in {@code 001-version-manifest.md}):
 * table prefix per deployment; chunks PK {@code (tenant, doc, ordinal)} for ordered
 * pagination; provenance PK {@code (tenant, doc, chunk)} for doc-scoped delete;
 * {@code text}/{@code ordinal}/{@code page} renamed ({@code chunk_text} etc.) to
 * avoid YQL reserved words; embeddings as Base64 {@code Utf8} + dim (024B pins the
 * native vector type); JSON content in {@code Json} columns.
 */
public class YdbSynvaultStore implements SynvaultStore, Conformant {

    private final TableClient client;
    private final String prefix;
    private final String namespace;
    private final AdapterMetrics metrics;

    /** Test-only failure hook: throws after chunk writes inside the revision tx. */
    public volatile boolean failAfterChunks;

    public YdbSynvaultStore(TableClient client, String tablePrefix) {
        this(client, tablePrefix, "", new InMemoryAdapterMetrics("ydb@1.0.0"));
    }

    public YdbSynvaultStore(TableClient client, String tablePrefix, AdapterMetrics metrics) {
        this(client, tablePrefix, "", metrics);
    }

    /**
     * Partitioned constructor: storage keys are namespaced so parallel stores stay
     * isolated without schema churn.
     */
    public YdbSynvaultStore(TableClient client, String tablePrefix, String namespace) {
        this(client, tablePrefix, namespace, new InMemoryAdapterMetrics("ydb@1.0.0"));
    }

    public YdbSynvaultStore(
            TableClient client, String tablePrefix, String namespace, AdapterMetrics metrics) {
        this.client = client;
        this.prefix = tablePrefix;
        this.namespace = namespace == null ? "" : namespace;
        this.metrics = metrics;
    }

    private String docs() {
        return "`" + prefix + "_documents`";
    }

    private String chunks() {
        return "`" + prefix + "_chunks`";
    }

    private String prov() {
        return "`" + prefix + "_provenance`";
    }

    private String pubs() {
        return "`" + prefix + "_publications`";
    }

    // ---- SynvaultStore ----

    @Override
    public CompletionStage<Document> putDocument(
            SecurityContext context, Document document, DocumentWriteOptions options) {
        long start = System.nanoTime();
        String op = AdapterMetrics.SYNVAULT_PUT;
        try (Session session = session()) {
            String tenant = tenant(context);
            Optional<Document> existing = selectDoc(session, tenant, document.id());
            long revision = existing.map(Document::storageRevision).orElse(0L);
            Instant created = existing.map(Document::createdAt).orElse(Instant.now());
            Instant now = Instant.now();
            exec(
                    session,
                    "DECLARE $t AS Utf8; DECLARE $d AS Utf8; DECLARE $title AS Utf8;"
                            + " DECLARE $uri AS Utf8; DECLARE $meta AS Json; DECLARE $rev AS Uint64;"
                            + " DECLARE $ca AS Timestamp; DECLARE $ua AS Timestamp;"
                            + "UPSERT INTO " + docs()
                            + " (tenant_id, doc_id, title, source_uri, metadata_json, storage_revision,"
                            + " created_at, updated_at) VALUES"
                            + " ($t, $d, $title, $uri, $meta, $rev, $ca, $ua);",
                    Params.create()
                            .put("$t", PrimitiveValue.newText(tenant))
                            .put("$d", PrimitiveValue.newText(document.id().value()))
                            .put("$title", PrimitiveValue.newText(document.title()))
                            .put("$uri", PrimitiveValue.newText(document.sourceUri()))
                            .put("$meta", PrimitiveValue.newJson(toJson(document.metadata())))
                            .put("$rev", PrimitiveValue.newUint64(revision))
                            .put("$ca", PrimitiveValue.newTimestamp(created))
                            .put("$ua", PrimitiveValue.newTimestamp(now)));
            Document stored =
                    new Document(
                            document.id(), document.title(), document.sourceUri(),
                            document.metadata(), revision, created, now);
            metrics.record(op, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(stored);
        } catch (RuntimeException e) {
            metrics.record(op, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public CompletionStage<Optional<Document>> getDocument(SecurityContext context, DocumentId id) {
        long start = System.nanoTime();
        try (Session session = session()) {
            Optional<Document> found = selectDoc(session, tenant(context), id);
            metrics.record(AdapterMetrics.SYNVAULT_GET, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(found);
        } catch (RuntimeException e) {
            metrics.record(AdapterMetrics.SYNVAULT_GET, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public CompletionStage<Void> deleteDocument(SecurityContext context, DocumentId id) {
        long start = System.nanoTime();
        String op = AdapterMetrics.SYNVAULT_DELETE;
        try (Session session = session()) {
            String tenant = tenant(context);
            exec(
                    session,
                    "DECLARE $t AS Utf8; DECLARE $d AS Utf8;"
                            + "DELETE FROM " + prov() + " WHERE tenant_id=$t AND doc_id=$d;",
                    Params.create()
                            .put("$t", PrimitiveValue.newText(tenant))
                            .put("$d", PrimitiveValue.newText(id.value())));
            exec(
                    session,
                    "DECLARE $t AS Utf8; DECLARE $d AS Utf8;"
                            + "DELETE FROM " + chunks() + " WHERE tenant_id=$t AND doc_id=$d;",
                    Params.create()
                            .put("$t", PrimitiveValue.newText(tenant))
                            .put("$d", PrimitiveValue.newText(id.value())));
            exec(
                    session,
                    "DECLARE $t AS Utf8; DECLARE $d AS Utf8;"
                            + "DELETE FROM " + docs() + " WHERE tenant_id=$t AND doc_id=$d;",
                    Params.create()
                            .put("$t", PrimitiveValue.newText(tenant))
                            .put("$d", PrimitiveValue.newText(id.value())));
            metrics.record(op, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            metrics.record(op, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public CompletionStage<ChunkPage> getChunks(
            SecurityContext context, DocumentId documentId, ChunkQuery query, PageRequest page) {
        long start = System.nanoTime();
        try (Session session = session()) {
            String tenant = tenant(context);
            int from = page.cursor().map(cursor -> Integer.parseInt(cursor) + 1).orElse(0);
            DataQueryResult result =
                    query(
                            session,
                            "DECLARE $t AS Utf8; DECLARE $d AS Utf8; DECLARE $from AS Uint32;"
                                    + " DECLARE $n AS Uint32;"
                                    + "SELECT chunk_id, chunk_ordinal, chunk_text, token_count, metadata_json,"
                                    + " embedding_b64, embedding_dim FROM " + chunks()
                                    + " WHERE tenant_id=$t AND doc_id=$d AND chunk_ordinal >= $from"
                                    + " ORDER BY chunk_ordinal LIMIT $n;",
                            Params.create()
                                    .put("$t", PrimitiveValue.newText(tenant))
                                    .put("$d", PrimitiveValue.newText(documentId.value()))
                                    .put("$from", PrimitiveValue.newUint32(from))
                                    .put("$n", PrimitiveValue.newUint32(page.limit() + 1)),
                            TxControl.snapshotRo().setCommitTx(true));
            List<Chunk> window = new ArrayList<>();
            ResultSetReader rs = result.getResultSet(0);
            while (rs.next()) {
                window.add(
                        new Chunk(
                                ChunkId.of(rs.getColumn("chunk_id").getText()),
                                documentId,
                                (int) rs.getColumn("chunk_ordinal").getUint32(),
                                rs.getColumn("chunk_text").getText(),
                                (int) rs.getColumn("token_count").getUint32(),
                                fromJson(rs.getColumn("metadata_json").getJson()),
                                decodeEmbedding(
                                        rs.getColumn("embedding_b64").getText(),
                                        (int) rs.getColumn("embedding_dim").getUint32())));
            }
            List<Chunk> filtered =
                    window.stream()
                            .filter(c -> matches(c.metadata(), query.mustMatchMetadata()))
                            .sorted(Comparator.comparingInt(Chunk::ordinal))
                            .toList();
            List<Chunk> items = filtered.stream().limit(page.limit()).toList();
            Optional<String> nextCursor =
                    filtered.size() > items.size()
                            ? Optional.of(String.valueOf(items.get(items.size() - 1).ordinal()))
                            : Optional.empty();
            metrics.record(AdapterMetrics.SYNVAULT_CHUNKS, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(new ChunkPage(items, nextCursor));
        } catch (RuntimeException e) {
            metrics.record(AdapterMetrics.SYNVAULT_CHUNKS, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public CompletionStage<Void> putDocumentRevision(
            SecurityContext context, DocumentRevision revision, RevisionWriteOptions options) {
        long start = System.nanoTime();
        String op = AdapterMetrics.SYNVAULT_REVISION;
        String tenant = tenant(context);
        DocumentId docId = revision.document().id();
        try (Session session = session()) {
            var tx =
                    session
                            .beginTransaction(TxMode.SERIALIZABLE_RW, new BeginTxSettings())
                            .join()
                            .getValue();
            try {
                DataQueryResult current =
                        tx.executeDataQuery(
                                        "DECLARE $t AS Utf8; DECLARE $d AS Utf8;"
                                                + "SELECT storage_revision, created_at FROM " + docs()
                                                + " WHERE tenant_id=$t AND doc_id=$d;",
                                        false,
                                        Params.create()
                                                .put("$t", PrimitiveValue.newText(tenant))
                                                .put("$d", PrimitiveValue.newText(docId.value())),
                                        new ExecuteDataQuerySettings())
                                .join()
                                .getValue();
                long stored = 0L;
                Instant created = Instant.now();
                ResultSetReader rs = current.getResultSet(0);
                if (rs.next()) {
                    stored = rs.getColumn("storage_revision").getUint64();
                    created = rs.getColumn("created_at").getTimestamp();
                }
                if (options.expectedRevision().isPresent()
                        && options.expectedRevision().get() != stored) {
                    tx.rollback().join();
                    metrics.record(op, System.nanoTime() - start, false);
                    return CompletableFuture.failedFuture(
                            new StorageException(
                                    StorageErrorKind.CONFLICT,
                                    "CONFLICT: expected revision " + options.expectedRevision().get()
                                            + " but stored is " + stored));
                }
                long committed = stored + 1;
                Instant now = Instant.now();
                execTx(
                        tx,
                        "DECLARE $t AS Utf8; DECLARE $d AS Utf8; DECLARE $title AS Utf8;"
                                + " DECLARE $uri AS Utf8; DECLARE $meta AS Json; DECLARE $rev AS Uint64;"
                                + " DECLARE $ca AS Timestamp; DECLARE $ua AS Timestamp;"
                                + "UPSERT INTO " + docs()
                                + " (tenant_id, doc_id, title, source_uri, metadata_json, storage_revision,"
                                + " created_at, updated_at) VALUES"
                                + " ($t, $d, $title, $uri, $meta, $rev, $ca, $ua);",
                        Params.create()
                                .put("$t", PrimitiveValue.newText(tenant))
                                .put("$d", PrimitiveValue.newText(docId.value()))
                                .put("$title", PrimitiveValue.newText(revision.document().title()))
                                .put("$uri", PrimitiveValue.newText(revision.document().sourceUri()))
                                .put("$meta", PrimitiveValue.newJson(toJson(revision.document().metadata())))
                                .put("$rev", PrimitiveValue.newUint64(committed))
                                .put("$ca", PrimitiveValue.newTimestamp(created))
                                .put("$ua", PrimitiveValue.newTimestamp(now)));
                execTx(
                        tx,
                        "DECLARE $t AS Utf8; DECLARE $d AS Utf8;"
                                + "DELETE FROM " + prov() + " WHERE tenant_id=$t AND doc_id=$d;",
                        Params.create()
                                .put("$t", PrimitiveValue.newText(tenant))
                                .put("$d", PrimitiveValue.newText(docId.value())));
                execTx(
                        tx,
                        "DECLARE $t AS Utf8; DECLARE $d AS Utf8;"
                                + "DELETE FROM " + chunks() + " WHERE tenant_id=$t AND doc_id=$d;",
                        Params.create()
                                .put("$t", PrimitiveValue.newText(tenant))
                                .put("$d", PrimitiveValue.newText(docId.value())));
                for (Chunk chunk : revision.chunks()) {
                    execTx(
                            tx,
                            "DECLARE $t AS Utf8; DECLARE $c AS Utf8; DECLARE $d AS Utf8;"
                                    + " DECLARE $o AS Uint32; DECLARE $x AS Utf8; DECLARE $tc AS Uint32;"
                                    + " DECLARE $m AS Json; DECLARE $e AS Utf8; DECLARE $ed AS Uint32;"
                                    + "INSERT INTO " + chunks()
                                    + " (tenant_id, chunk_id, doc_id, chunk_ordinal, chunk_text,"
                                    + " token_count, metadata_json, embedding_b64, embedding_dim) VALUES"
                                    + " ($t, $c, $d, $o, $x, $tc, $m, $e, $ed);",
                            Params.create()
                                    .put("$t", PrimitiveValue.newText(tenant))
                                    .put("$c", PrimitiveValue.newText(chunk.id().value()))
                                    .put("$d", PrimitiveValue.newText(docId.value()))
                                    .put("$o", PrimitiveValue.newUint32(chunk.ordinal()))
                                    .put("$x", PrimitiveValue.newText(chunk.text()))
                                    .put("$tc", PrimitiveValue.newUint32(chunk.tokenCount()))
                                    .put("$m", PrimitiveValue.newJson(toJson(chunk.metadata())))
                                    .put("$e", PrimitiveValue.newText(encodeEmbedding(chunk.embedding())))
                                    .put("$ed", PrimitiveValue.newUint32(
                                            chunk.embedding() == null ? 0 : chunk.embedding().length)));
                }
                if (failAfterChunks) {
                    throw new IllegalStateException("injected failure after chunk writes");
                }
                for (ProvenanceRecord record : revision.provenance()) {
                    execTx(
                            tx,
                            "DECLARE $t AS Utf8; DECLARE $d AS Utf8; DECLARE $c AS Utf8;"
                                    + " DECLARE $ex AS Utf8; DECLARE $sv AS Utf8; DECLARE $mr AS Json;"
                                    + " DECLARE $pg AS Uint32; DECLARE $s AS Uint32; DECLARE $e AS Uint32;"
                                    + "INSERT INTO " + prov()
                                    + " (tenant_id, doc_id, chunk_id, extractor, source_version_id,"
                                    + " model_ref_json, page_num, start_offset, end_offset) VALUES"
                                    + " ($t, $d, $c, $ex, $sv, $mr, $pg, $s, $e);",
                            Params.create()
                                    .put("$t", PrimitiveValue.newText(tenant))
                                    .put("$d", PrimitiveValue.newText(docId.value()))
                                    .put("$c", PrimitiveValue.newText(record.chunkId().value()))
                                    .put("$ex", PrimitiveValue.newText(record.extractor()))
                                    .put("$sv", PrimitiveValue.newText(record.sourceVersionId().value()))
                                    .put("$mr", PrimitiveValue.newJson(
                                            record.embeddingModelRef()
                                                    .map(m -> "{\"id\":\"" + m.modelId() + "\",\"v\":\""
                                                            + m.version() + "\",\"digest\":\"" + m.digest() + "\"}")
                                                    .orElse("{}")))
                                    .put("$pg", PrimitiveValue.newUint32(record.page()))
                                    .put("$s", PrimitiveValue.newUint32(record.startOffset()))
                                    .put("$e", PrimitiveValue.newUint32(record.endOffset())));
                }
                execTx(
                        tx,
                        "DECLARE $t AS Utf8; DECLARE $r AS Utf8; DECLARE $p AS Json; DECLARE $ca AS Timestamp;"
                                + "INSERT INTO " + pubs()
                                + " (tenant_id, revision_id, payload_json, created_at) VALUES"
                                + " ($t, $r, $p, $ca);",
                        Params.create()
                                .put("$t", PrimitiveValue.newText(tenant))
                                .put("$r", PrimitiveValue.newText(revision.publication().revisionId()))
                                .put("$p", PrimitiveValue.newJson(revision.publication().payloadJson()))
                                .put("$ca", PrimitiveValue.newTimestamp(now)));
                tech.ydb.core.Status committedStatus = tx.commit().join();
                if (!committedStatus.isSuccess()) {
                    throw new IllegalStateException("commit failed: " + committedStatus);
                }
                metrics.record(op, System.nanoTime() - start, true);
                return CompletableFuture.completedFuture(null);
            } catch (RuntimeException e) {
                try {
                    tx.rollback().join();
                } catch (RuntimeException ignored) {
                    // Best effort: the transaction aborts server-side on session close regardless.
                }
                metrics.record(op, System.nanoTime() - start, false);
                throw e;
            }
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            metrics.record(op, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    /**
     * Relay seam for the 029 projection consumer (and the future 1.27 client):
     * publication records committed but not yet handed to eventing, tenant-scoped
     * (031) — a relay must never scan across tenants. Ordered oldest-first.
     */
    public CompletionStage<List<PublicationIntent>> pendingPublications(
            SecurityContext context, int limit) {
        try (Session session = session()) {
            String tenant = tenant(context);
            DataQueryResult result =
                    query(
                            session,
                            "DECLARE $t AS Utf8; DECLARE $n AS Uint32;"
                                    + "SELECT revision_id, payload_json, created_at FROM " + pubs()
                                    + " WHERE tenant_id=$t AND published_at IS NULL"
                                    + " ORDER BY created_at LIMIT $n;",
                            Params.create()
                                    .put("$t", PrimitiveValue.newText(tenant))
                                    .put("$n", PrimitiveValue.newUint32(limit)),
                            TxControl.snapshotRo().setCommitTx(true));
            List<PublicationIntent> out = new ArrayList<>();
            ResultSetReader rs = result.getResultSet(0);
            while (rs.next()) {
                out.add(
                        new PublicationIntent(
                                rs.getColumn("revision_id").getText(),
                                tenant,
                                rs.getColumn("payload_json").getJson()));
            }
            return CompletableFuture.completedFuture(out);
        } catch (RuntimeException e) {
            throw map(e);
        }
    }

    /**
     * Marks a publication record handed to eventing (sets {@code published_at}).
     * Idempotent: re-marking an already-published record is a no-op match.
     */
    public CompletionStage<Void> markPublished(SecurityContext context, String revisionId) {
        try (Session session = session()) {
            exec(
                    session,
                    "DECLARE $t AS Utf8; DECLARE $r AS Utf8;"
                            + "UPDATE " + pubs() + " SET published_at=CurrentUtcTimestamp()"
                            + " WHERE tenant_id=$t AND revision_id=$r;",
                    Params.create()
                            .put("$t", PrimitiveValue.newText(tenant(context)))
                            .put("$r", PrimitiveValue.newText(revisionId)));
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            throw map(e);
        }
    }

    @Override
    public CompletionStage<List<ProvenanceRecord>> getProvenance(SecurityContext context, DocumentId id) {        long start = System.nanoTime();
        try (Session session = session()) {
            String tenant = tenant(context);
            DataQueryResult result =
                    query(
                            session,
                            "DECLARE $t AS Utf8; DECLARE $d AS Utf8;"
                                    + "SELECT chunk_id, extractor, source_version_id, model_ref_json,"
                                    + " page_num, start_offset, end_offset FROM " + prov()
                                    + " WHERE tenant_id=$t AND doc_id=$d;",
                            Params.create()
                                    .put("$t", PrimitiveValue.newText(tenant))
                                    .put("$d", PrimitiveValue.newText(id.value())),
                            TxControl.snapshotRo().setCommitTx(true));
            List<ProvenanceRecord> out = new ArrayList<>();
            ResultSetReader rs = result.getResultSet(0);
            while (rs.next()) {
                out.add(
                        new ProvenanceRecord(
                                ChunkId.of(rs.getColumn("chunk_id").getText()),
                                rs.getColumn("extractor").getText(),
                                SourceVersionId.of(rs.getColumn("source_version_id").getText()),
                                Optional.empty(),
                                (int) rs.getColumn("page_num").getUint32(),
                                (int) rs.getColumn("start_offset").getUint32(),
                                (int) rs.getColumn("end_offset").getUint32()));
            }
            metrics.record(AdapterMetrics.SYNVAULT_PROVENANCE, System.nanoTime() - start, true);
            return CompletableFuture.completedFuture(out);
        } catch (RuntimeException e) {
            metrics.record(AdapterMetrics.SYNVAULT_PROVENANCE, System.nanoTime() - start, false);
            throw map(e);
        }
    }

    @Override
    public StoreCapabilities capabilities() {
        return new StoreCapabilities(true, ConsistencyLevel.STRONG, false, true, true, true);
    }

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
        String evidence = "org.synanton.synvault.ydb.YdbSynvaultStoreTest";
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

    private void execTx(
            tech.ydb.table.transaction.TableTransaction tx, String yql, Params params) {
        tx.executeDataQuery(yql, false, params, new ExecuteDataQuerySettings()).join().getValue();
    }

    private Optional<Document> selectDoc(Session session, String tenant, DocumentId id) {
        DataQueryResult result =
                query(
                        session,
                        "DECLARE $t AS Utf8; DECLARE $d AS Utf8;"
                                + "SELECT title, source_uri, metadata_json, storage_revision,"
                                + " created_at, updated_at FROM " + docs()
                                + " WHERE tenant_id=$t AND doc_id=$d;",
                        Params.create()
                                .put("$t", PrimitiveValue.newText(tenant))
                                .put("$d", PrimitiveValue.newText(id.value())),
                        TxControl.snapshotRo().setCommitTx(true));
        ResultSetReader rs = result.getResultSet(0);
        if (!rs.next()) {
            return Optional.empty();
        }
        return Optional.of(
                new Document(
                        id,
                        rs.getColumn("title").getText(),
                        rs.getColumn("source_uri").getText(),
                        fromJson(rs.getColumn("metadata_json").getJson()),
                        rs.getColumn("storage_revision").getUint64(),
                        rs.getColumn("created_at").getTimestamp(),
                        rs.getColumn("updated_at").getTimestamp()));
    }

    private String tenant(SecurityContext context) {
        String tenant = context.tenantScope().tenantId();
        return namespace.isEmpty() ? tenant : namespace + "|" + tenant;
    }

    private static RuntimeException map(RuntimeException e) {
        if (e instanceof StorageException) {
            return e;
        }
        String message = String.valueOf(e.getMessage());
        if (message.contains("ABORTED")) {
            // Version-coupled: YDB OCC conflict detection relies on ABORTED (observed as
            // code 400040 on 26.3.x). Re-validate this mapping on any YDB version change
            // (same discipline as preview-feature flags in 003).
            return new StorageException(StorageErrorKind.CONFLICT, "CONFLICT: YDB serialization abort: " + message);
        }
        return new StorageException(StorageErrorKind.TRANSIENT, "YDB failure: " + message);
    }

    private static boolean matches(Map<String, String> metadata, Map<String, String> required) {
        for (Map.Entry<String, String> entry : required.entrySet()) {
            if (!entry.getValue().equals(metadata.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    static String toJson(Map<String, String> metadata) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    static Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank() || json.trim().equals("{}")) {
            return Map.of();
        }
        String body = json.trim();
        body = body.substring(1, body.length() - 1);
        if (body.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new java.util.HashMap<>();
        for (String pair : body.split("\",\"")) {
            String[] kv = pair.replaceFirst("^\"", "").split("\":\"", 2);
            if (kv.length == 2) {
                out.put(unescape(kv[0]), unescape(kv[1].replaceFirst("\"$", "")));
            }
        }
        return Map.copyOf(out);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static String encodeEmbedding(float[] embedding) {
        if (embedding == null) {
            return "";
        }
        ByteBuffer buf = ByteBuffer.allocate(4 * embedding.length);
        for (float v : embedding) {
            buf.putFloat(v);
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }

    static float[] decodeEmbedding(String base64, int dim) {
        if (base64 == null || base64.isEmpty() || dim <= 0) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length != 4 * dim) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] out = new float[dim];
        for (int i = 0; i < dim; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }
}
