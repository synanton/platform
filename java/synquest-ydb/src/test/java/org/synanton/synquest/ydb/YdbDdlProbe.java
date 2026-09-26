package org.synanton.synquest.ydb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tech.ydb.core.Status;
import tech.ydb.table.Session;

@EnabledIfSystemProperty(named = "ydb.probe", matches = "true")
class YdbDdlProbe {

    private static void step(Session session, String label, String yql) {
        try {
            Status s = session.executeSchemeQuery(yql).join();
            System.out.println("DDLPROBE " + label + " -> " + s.isSuccess() + " " + s);
        } catch (Exception e) {
            System.out.println("DDLPROBE " + label + " -> threw " + e.getMessage());
        }
    }

    @Test
    void createThenAlter() {
        YdbSearchTestBase.ensureStarted();
        String p = YdbSearchTestBase.randomPrefix();
        try (Session session = YdbSearchTestBase.session()) {
            step(session, "create", "CREATE TABLE `" + p + "_t` (a Utf8 NOT NULL, b Utf8 NOT NULL,"
                    + " PRIMARY KEY (a, b));");
            step(session, "alter-ft",
                    "ALTER TABLE `" + p + "_t` ADD INDEX `ft` GLOBAL USING fulltext_relevance"
                            + " ON (`b`) WITH (tokenizer=standard, use_filter_lowercase=true);");
            step(session, "describe-check",
                    "SELECT * FROM `" + p + "_t` LIMIT 0;");
            step(session, "drop", "DROP TABLE `" + p + "_t`;");
        }
    }
}
