package org.synanton.synquest.ydb;

import tech.ydb.core.Status;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;

/**
 * 024B search schema: projections table (lexical FT), vectors table (filtered +
 * plain vector indexes, FT index), generations pointer table.
 *
 * <p>Lifecycle rule: fixed per-class prefixes, idempotent ensure + truncate per
 * run — never random prefixes (10k path quota) and never inline copies.
 * Existence is probed (SELECT LIMIT 0), never message-matched: YDB's
 * already-exists errors vary by path state.
 */
public final class YdbSearchSchema {

    private YdbSearchSchema() {}

    /** Idempotent variant for fixed-name lifecycles (no path growth). */
    public static void ensureSchema(TableClient client, String prefix, int embeddingDim) {
        // Definitions below must match ensureSchema exactly (single DDL source would be
        // ideal; the duplication is structural — table names differ per call site).
        try (Session session =
                client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
            executeIfAbsent(
                    session,
                    prefix + "_generations",
                    "CREATE TABLE `" + prefix + "_generations` ("
                            + "scope Utf8 NOT NULL, active_generation Utf8 NOT NULL,"
                            + " PRIMARY KEY (scope));");
            executeIfAbsent(
                    session,
                    prefix + "_projections",
                    "CREATE TABLE `" + prefix + "_projections` ("
                            + "tenant_id Utf8 NOT NULL, chunk_id Utf8 NOT NULL, doc_id Utf8 NOT NULL,"
                            + " chunk_text Utf8 NOT NULL, metadata_json Json NOT NULL,"
                            + " ordering_key Uint64 NOT NULL, generation Utf8 NOT NULL,"
                            + " PRIMARY KEY (tenant_id, chunk_id));",
                    "ALTER TABLE `" + prefix + "_projections` ADD INDEX `ft` GLOBAL USING"
                            + " fulltext_relevance ON (`chunk_text`)"
                            + " WITH (tokenizer=standard, use_filter_lowercase=true);");
            executeIfAbsent(
                    session,
                    prefix + "_vectors",
                    "CREATE TABLE `" + prefix + "_vectors` ("
                            + "key Utf8 NOT NULL, tenant_id Utf8 NOT NULL, chunk_id Utf8 NOT NULL,"
                            + " doc_id Utf8 NOT NULL, chunk_text Utf8 NOT NULL,"
                            + " metadata_json Json NOT NULL, embedding String NOT NULL,"
                            + " ordering_key Uint64 NOT NULL, generation Utf8 NOT NULL,"
                            + " PRIMARY KEY (key));",
                    "ALTER TABLE `" + prefix + "_vectors` ADD INDEX `v_vec` GLOBAL USING"
                            + " vector_kmeans_tree ON (`tenant_id`, `embedding`)"
                            + " WITH (distance=cosine, vector_type=\"float\","
                            + " vector_dimension=" + embeddingDim + ");",
                    "ALTER TABLE `" + prefix + "_vectors` ADD INDEX `v_hyb` GLOBAL USING"
                            + " vector_kmeans_tree ON (`embedding`)"
                            + " WITH (distance=cosine, vector_type=\"float\","
                            + " vector_dimension=" + embeddingDim + ");",
                    "ALTER TABLE `" + prefix + "_vectors` ADD INDEX `v_ft` GLOBAL USING"
                            + " fulltext_relevance ON (`chunk_text`)"
                            + " WITH (tokenizer=standard, use_filter_lowercase=true);");
        }
    }

    /** Clears search tables for a prefix (synchronous, unlike DROP). */
    public static void truncateAll(TableClient client, String prefix) {
        try (Session session =
                client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
            for (String table :
                    java.util.List.of("projections", "vectors", "generations")) {
                execute(session, "TRUNCATE TABLE `" + prefix + "_" + table + "`;");
            }
        }
    }

    /** Best-effort cleanup (tables + implicit index paths). Never throws. */
    public static void dropSchema(TableClient client, String prefix) {
        for (String name :
                java.util.List.of(prefix + "_projections", prefix + "_vectors")) {
            try (Session session =
                    client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
                session.executeSchemeQuery("DROP TABLE `" + name + "`;").join();
            } catch (Exception ignored) {
                // Best effort: cleanup must never fail a test run.
            }
        }
    }

    public static void ensureSchema(TableClient client, String prefix) {
        ensureSchema(client, prefix, 384);
    }

    private static void execute(Session session, String yql) {
        Status status = session.executeSchemeQuery(yql).join();
        if (!status.isSuccess()) {
            throw new IllegalStateException(interpret(yql, String.valueOf(status)));
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

    /**
     * Maps index-creation failures to the missing capability/flag (fail fast with
     * the gap named, not a raw server error). Unit-tested below via synthetic messages.
     */
    static String interpret(String yql, String status) {
        String hint = "";
        if (status.contains("__ydb_row_id")) {
            hint = " Likely missing: feature_flags.enable_fulltext_index_row_id"
                    + " (+ enable_add_unique_index / enable_online_add_unique_index)."
                    + " See ydb-poc-cluster-flags.yaml.";
        } else if (status.contains("Prefixed fulltext")) {
            hint = " Likely missing: feature_flags.enable_fulltext_index_prefix."
                    + " See ydb-poc-cluster-flags.yaml.";
        } else if (status.contains("unique-index") || status.contains("unique_index")) {
            hint = " Likely missing: enable_add_unique_index / enable_online_add_unique_index.";
        } else if (status.contains("VECTOR_") && status.contains("not supported")) {
            hint = " Wrong index subtype: use vector_kmeans_tree (see 002-schema-validation.md).";
        }
        return "DDL failed: " + yql + " -> " + status + hint;
    }
}
