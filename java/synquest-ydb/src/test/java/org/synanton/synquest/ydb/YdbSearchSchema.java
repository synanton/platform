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

    public static void ensureSchema(TableClient client, String prefix) {
        String table = "`" + prefix + "_projections`";
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
        }
    }

    private static void execute(Session session, String yql) {
        Status status = session.executeSchemeQuery(yql).join();
        if (!status.isSuccess()) {
            throw new IllegalStateException("DDL failed: " + yql + " -> " + status);
        }
    }
}
