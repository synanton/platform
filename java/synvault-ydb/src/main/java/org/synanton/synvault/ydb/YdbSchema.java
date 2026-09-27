package org.synanton.synvault.ydb;

import tech.ydb.core.Status;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;

/**
 * §11.1 schema (with PoC deviations recorded in {@code 001-version-manifest.md}):
 * table prefix per deployment; chunks PK {@code (tenant, doc, ordinal)};
 * provenance PK {@code (tenant, doc, chunk)}; YQL-reserved words renamed
 * ({@code chunk_text}, {@code chunk_ordinal}, {@code page_num}); embeddings as
 * Base64 {@code Utf8} + dim (native vector type pinned in 024B).
 *
 * <p>Lifecycle rule (quota-safe): exactly one DDL definition per table lives
 * here. Tests use fixed per-class prefixes via {@link #prepare} (create once,
 * truncate per run) — never random prefixes (10k path quota) and never inline
 * copies (copies drift; see P0-1 relay incident).
 */
public final class YdbSchema {

    private YdbSchema() {}

    private static String docs(String prefix) {
        return "`" + prefix + "_documents`";
    }

    private static String chunks(String prefix) {
        return "`" + prefix + "_chunks`";
    }

    private static String prov(String prefix) {
        return "`" + prefix + "_provenance`";
    }

    private static String pubs(String prefix) {
        return "`" + prefix + "_publications`";
    }

    /** Creates the table set; fails loudly on any error. */


    /** Idempotent: existing tables are kept (probed, never message-matched). */
    public static void ensureSchema(TableClient client, String prefix) {
        try (Session session = client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
            executeIfAbsent(session, prefix + "_documents", "CREATE TABLE " + docs(prefix) + " (tenant_id Utf8 NOT NULL,"
                    + " doc_id Utf8 NOT NULL, title Utf8 NOT NULL, source_uri Utf8 NOT NULL,"
                    + " metadata_json Json NOT NULL, storage_revision Uint64 NOT NULL,"
                    + " created_at Timestamp NOT NULL, updated_at Timestamp NOT NULL,"
                    + " PRIMARY KEY (tenant_id, doc_id));");
            executeIfAbsent(session, prefix + "_chunks", "CREATE TABLE " + chunks(prefix) + " (tenant_id Utf8 NOT NULL,"
                    + " chunk_id Utf8 NOT NULL, doc_id Utf8 NOT NULL, chunk_ordinal Uint32 NOT NULL,"
                    + " chunk_text Utf8 NOT NULL, token_count Uint32 NOT NULL,"
                    + " metadata_json Json NOT NULL, embedding_b64 Utf8 NOT NULL,"
                    + " embedding_dim Uint32 NOT NULL,"
                    + " PRIMARY KEY (tenant_id, doc_id, chunk_ordinal));");
            executeIfAbsent(session, prefix + "_provenance", "CREATE TABLE " + prov(prefix) + " (tenant_id Utf8 NOT NULL,"
                    + " doc_id Utf8 NOT NULL, chunk_id Utf8 NOT NULL, extractor Utf8 NOT NULL,"
                    + " source_version_id Utf8 NOT NULL, model_ref_json Json NOT NULL,"
                    + " page_num Uint32 NOT NULL, start_offset Uint32 NOT NULL,"
                    + " end_offset Uint32 NOT NULL,"
                    + " PRIMARY KEY (tenant_id, doc_id, chunk_id));");
            executeIfAbsent(session, prefix + "_publications", "CREATE TABLE " + pubs(prefix) + " (tenant_id Utf8 NOT NULL,"
                    + " revision_id Utf8 NOT NULL, payload_json Json NOT NULL,"
                    + " created_at Timestamp NOT NULL, published_at Timestamp,"
                    + " PRIMARY KEY (tenant_id, revision_id));");
        }
    }

    /** Best-effort cleanup. Never throws. Prefer {@link #truncateAll} per run. */
    public static void dropSchema(TableClient client, String prefix) {
        for (String name :
                java.util.List.of(
                        prefix + "_documents",
                        prefix + "_chunks",
                        prefix + "_provenance",
                        prefix + "_publications")) {
            try (Session session =
                    client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
                session.executeSchemeQuery("DROP TABLE `" + name + "`;").join();
            } catch (Exception ignored) {
                // Best effort: cleanup must never fail a test run.
            }
        }
    }

    /** Ad-hoc DDL for tests that own their schema. */
    public static void ddl(TableClient client, String yql) {
        try (Session session =
                client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
            execute(session, yql);
        }
    }

    /** Clears all vault tables for a prefix (synchronous, unlike DROP). */
    public static void truncateAll(TableClient client, String prefix) {
        try (Session session =
                client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
            for (String table :
                    java.util.List.of("documents", "chunks", "provenance", "publications")) {
                execute(session, "TRUNCATE TABLE `" + prefix + "_" + table + "`;");
            }
        }
    }

    private static void execute(Session session, String yql) {
        Status status = session.executeSchemeQuery(yql).join();
        if (!status.isSuccess()) {
            throw new IllegalStateException("DDL failed: " + yql + " -> " + status);
        }
    }

    private static void executeIfAbsent(Session session, String tableName, String... statements) {
        try {
            session.executeDataQuery(
                            "SELECT * FROM `" + tableName + "` LIMIT 0;",
                            tech.ydb.table.transaction.TxControl.snapshotRo().setCommitTx(true),
                            tech.ydb.table.query.Params.empty(),
                            new tech.ydb.table.settings.ExecuteDataQuerySettings())
                    .join()
                    .getValue();
            return;
        } catch (RuntimeException e) {
            // Absent — create below.
        }
        for (String yql : statements) {
            execute(session, yql);
        }
    }
}
