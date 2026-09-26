package org.synanton.synvault.ydb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tech.ydb.table.Session;

/**
 * Gate 0 probe (Phase-2 pre-flight): discovers whether YDB 26.3 supports vector
 * ANN + full-text indexes and how eligibility predicates compose with them.
 * Runs ONLY with {@code -Dydb.probe=true}. Tries a ladder of candidate DDL
 * statements and reports what the server accepts — no assumptions baked in.
 */
@EnabledIfSystemProperty(named = "ydb.probe", matches = "true")
class YdbIndexProbe {

    private static void attempt(Session session, String label, String yql) {
        try {
            tech.ydb.core.Status status = session.executeSchemeQuery(yql).join();
            System.out.println("PROBE " + label + " -> success=" + status.isSuccess() + " " + status);
        } catch (Exception e) {
            System.out.println("PROBE " + label + " -> threw " + e.getMessage());
        }
    }

    @Test
    void probeIndexCapabilities() {
        YdbTestBase.ensureStarted();
        try (Session session = YdbTestBase.session()) {
            attempt(session, "drop-leftovers",
                    "DROP TABLE `probe_vec`;");
            attempt(session, "base-table",
                    "CREATE TABLE `probe_vec` (id Utf8 NOT NULL, tenant Utf8 NOT NULL,"
                            + " text Utf8 NOT NULL, embedding String NOT NULL,"
                            + " PRIMARY KEY (id));");
            attempt(session, "plain-global-index",
                    "ALTER TABLE `probe_vec` ADD INDEX `by_tenant` GLOBAL ON (`tenant`);");
            attempt(session, "plain-global-on-embedding",
                    "ALTER TABLE `probe_vec` ADD INDEX `emb_plain` GLOBAL ON (`embedding`);");
            attempt(session, "vector-flat-using",
                    "ALTER TABLE `probe_vec` ADD INDEX `vec_flat` GLOBAL USING vector_flat"
                            + " ON (`embedding`);");
            attempt(session, "vector-with-clause",
                    "ALTER TABLE `probe_vec` ADD INDEX `vec_with` GLOBAL ON (`embedding`)"
                            + " WITH (vector_distance=cosine, vector_dimension=3);");
            attempt(session, "plain-global-on-text",
                    "ALTER TABLE `probe_vec` ADD INDEX `txt_plain` GLOBAL ON (`text`);");
            attempt(session, "fulltext-using",
                    "ALTER TABLE `probe_vec` ADD INDEX `ft_use` GLOBAL USING fulltext"
                            + " ON (`text`);");
            attempt(session, "cleanup", "DROP TABLE `probe_vec`;");
        }
    }
}
