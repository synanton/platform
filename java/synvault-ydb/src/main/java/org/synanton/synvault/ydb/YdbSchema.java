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
 */
public final class YdbSchema {

    private YdbSchema() {}

    public static void ensureSchema(TableClient client, String prefix) {        String docs = "`" + prefix + "_documents`";
        String chunks = "`" + prefix + "_chunks`";
        String prov = "`" + prefix + "_provenance`";
        String pubs = "`" + prefix + "_publications`";
        try (Session session = client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
            execute(
                    session,
                    "CREATE TABLE " + docs + " ("
                            + "tenant_id Utf8 NOT NULL, doc_id Utf8 NOT NULL, title Utf8 NOT NULL,"
                            + " source_uri Utf8 NOT NULL, metadata_json Json NOT NULL,"
                            + " storage_revision Uint64 NOT NULL,"
                            + " created_at Timestamp NOT NULL, updated_at Timestamp NOT NULL,"
                            + " PRIMARY KEY (tenant_id, doc_id));");
            execute(
                    session,
                    "CREATE TABLE " + chunks + " ("
                            + "tenant_id Utf8 NOT NULL, chunk_id Utf8 NOT NULL, doc_id Utf8 NOT NULL,"
                            + " chunk_ordinal Uint32 NOT NULL, chunk_text Utf8 NOT NULL,"
                            + " token_count Uint32 NOT NULL, metadata_json Json NOT NULL,"
                            + " embedding_b64 Utf8 NOT NULL, embedding_dim Uint32 NOT NULL,"
                            + " PRIMARY KEY (tenant_id, doc_id, chunk_ordinal));");
            execute(
                    session,
                    "CREATE TABLE " + prov + " ("
                            + "tenant_id Utf8 NOT NULL, doc_id Utf8 NOT NULL, chunk_id Utf8 NOT NULL,"
                            + " extractor Utf8 NOT NULL, source_version_id Utf8 NOT NULL,"
                            + " model_ref_json Json NOT NULL, page_num Uint32 NOT NULL,"
                            + " start_offset Uint32 NOT NULL, end_offset Uint32 NOT NULL,"
                            + " PRIMARY KEY (tenant_id, doc_id, chunk_id));");
            execute(
                    session,
                    "CREATE TABLE " + pubs + " ("
                            + "tenant_id Utf8 NOT NULL, revision_id Utf8 NOT NULL,"
                            + " payload_json Json NOT NULL, created_at Timestamp NOT NULL,"
                            + " published_at Timestamp,"
                            + " PRIMARY KEY (tenant_id, revision_id));");
        }
    }

    /** Ad-hoc DDL for tests that own their schema (e.g. relay search tables). */
    /** Best-effort cleanup (tables + implicit index paths). Never throws. */
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

    public static void ddl(TableClient client, String yql) {
        try (Session session =
                client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
            execute(session, yql);
        }
    }

    private static void execute(Session session, String yql) {
        Status status = session.executeSchemeQuery(yql).join();
        if (!status.isSuccess()) {
            throw new IllegalStateException("DDL failed: " + yql + " -> " + status);
        }
    }
}
