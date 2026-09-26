package org.synanton.synquest.ydb;

import tech.ydb.core.Status;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;

/**
 * 024B projection table + lexical full-text index (commit 1). Vector index and
 * hybrid readiness land in commits 2–3 on a separate index structure
 * (024-scope-split decision — this schema stays stable).
 */
public final class YdbSearchSchema {

    private YdbSearchSchema() {}

    public static void ensureSchema(TableClient client, String prefix, int embeddingDim) {
        String table = "`" + prefix + "_projections`";
        String vectors = "`" + prefix + "_vectors`";
        try (Session session =
                client.createSession(java.time.Duration.ofSeconds(10)).join().getValue()) {
            execute(
                    session,
                    "CREATE TABLE " + table + " ("
                            + "tenant_id Utf8 NOT NULL, chunk_id Utf8 NOT NULL, doc_id Utf8 NOT NULL,"
                            + " chunk_text Utf8 NOT NULL, metadata_json Json NOT NULL,"
                            + " ordering_key Uint64 NOT NULL, generation Utf8 NOT NULL,"
                            + " PRIMARY KEY (tenant_id, chunk_id));");
            execute(
                    session,
                    "ALTER TABLE " + table + " ADD INDEX `ft` GLOBAL USING fulltext_relevance"
                            + " ON (`chunk_text`)"
                            + " WITH (tokenizer=standard, use_filter_lowercase=true);");
            execute(
                    session,
                    "CREATE TABLE " + vectors + " ("
                            + "tenant_id Utf8 NOT NULL, chunk_id Utf8 NOT NULL, doc_id Utf8 NOT NULL,"
                            + " metadata_json Json NOT NULL, embedding String NOT NULL,"
                            + " ordering_key Uint64 NOT NULL, generation Utf8 NOT NULL,"
                            + " PRIMARY KEY (tenant_id, chunk_id));");
            execute(
                    session,
                    "ALTER TABLE " + vectors + " ADD INDEX `v_vec` GLOBAL USING vector_kmeans_tree"
                            + " ON (`tenant_id`, `embedding`)"
                            + " WITH (distance=cosine, vector_type=\"float\","
                            + " vector_dimension=" + embeddingDim + ");");
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
