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
    void dropTableWithIndexes() {
        YdbSearchTestBase.ensureStarted();
        String prefix = YdbSearchTestBase.randomPrefix();
        YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
        String table = "`" + prefix + "_vectors`";
        try (Session session = YdbSearchTestBase.session()) {
            System.out.println("DDLPROBE drop-indexed -> "
                    + session.executeSchemeQuery("DROP TABLE " + table + ";").join());
        }
    }

    @Test
    void dropIndexedTableRemovesPaths() {
        YdbSearchTestBase.ensureStarted();
        String prefix = YdbSearchTestBase.randomPrefix();
        YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
        try (Session session = YdbSearchTestBase.session()) {
            System.out.println("DDLPROBE drop-full -> "
                    + session.executeSchemeQuery("DROP TABLE `" + prefix + "_vectors`;").join().isSuccess());
            System.out.println("DDLPROBE drop2 -> "
                    + session.executeSchemeQuery("DROP TABLE `" + prefix + "_projections`;").join().isSuccess());
        }
        // Verify via describe: both names must be gone.
        try (Session session = YdbSearchTestBase.session()) {
            var r = session.executeDataQuery("SELECT 1;", tech.ydb.table.transaction.TxControl.staleRo().setCommitTx(true),
                    tech.ydb.table.query.Params.empty(),
                    new tech.ydb.table.settings.ExecuteDataQuerySettings()).join();
            System.out.println("DDLPROBE alive -> " + (r != null));
        }
    }

    @Test
    void dropRemovesTable() {
        YdbSearchTestBase.ensureStarted();
        String name = "`drop_" + YdbSearchTestBase.randomPrefix() + "`";
        try (Session session = YdbSearchTestBase.session()) {
            var st = session.executeSchemeQuery(
                    "CREATE TABLE " + name + " (a Utf8 NOT NULL, PRIMARY KEY (a));").join();
            System.out.println("DDLPROBE mk -> " + st.isSuccess());
            var dr = session.executeSchemeQuery("DROP TABLE " + name + ";").join();
            System.out.println("DDLPROBE drop -> " + dr.isSuccess() + " " + dr);
        }
    }

    @Test
    void dropIndexThenTable() {
        YdbSearchTestBase.ensureStarted();
        String name = "gone2_" + YdbSearchTestBase.randomPrefix();
        try (Session session = YdbSearchTestBase.session()) {
            session.executeSchemeQuery("CREATE TABLE `" + name + "` (a Utf8 NOT NULL,"
                    + " embedding String NOT NULL, PRIMARY KEY (a));").join();
            session.executeSchemeQuery("ALTER TABLE `" + name + "` ADD INDEX `v`"
                    + " GLOBAL USING vector_kmeans_tree ON (`embedding`)"
                    + " WITH (distance=cosine, vector_type=\"float\", vector_dimension=2);").join();
            System.out.println("DDLPROBE drop-idx2 -> "
                    + session.executeSchemeQuery(
                            "ALTER TABLE `" + name + "` DROP INDEX `v`;").join().isSuccess());
            try { Thread.sleep(10000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            System.out.println("DDLPROBE drop-tbl2 -> "
                    + session.executeSchemeQuery("DROP TABLE `" + name + "`;").join().isSuccess());
            try {
                session.executeSchemeQuery("SELECT * FROM `" + name + "` LIMIT 0;").join();
                System.out.println("DDLPROBE still-present2");
            } catch (Exception e) {
                System.out.println("DDLPROBE gone2");
            }
        }
    }

    @Test
    void truncateIndexedTable() {
        YdbSearchTestBase.ensureStarted();
        String name = "`trunc_" + YdbSearchTestBase.randomPrefix() + "`";
        try (Session session = YdbSearchTestBase.session()) {
            session.executeSchemeQuery("CREATE TABLE " + name + " (a Utf8 NOT NULL,"
                    + " embedding String NOT NULL, PRIMARY KEY (a));").join();
            session.executeSchemeQuery("ALTER TABLE " + name + " ADD INDEX `v`"
                    + " GLOBAL USING vector_kmeans_tree ON (`embedding`)"
                    + " WITH (distance=cosine, vector_type=\"float\", vector_dimension=2);").join();
            session.executeDataQuery("UPSERT INTO " + name + " (a, embedding) VALUES ('k', 'x');",
                    tech.ydb.table.transaction.TxControl.serializableRw().setCommitTx(true),
                    tech.ydb.table.query.Params.empty(),
                    new tech.ydb.table.settings.ExecuteDataQuerySettings()).join().getValue();
            System.out.println("DDLPROBE truncate -> "
                    + session.executeSchemeQuery("TRUNCATE TABLE " + name + ";").join().isSuccess());
            var r = session.executeDataQuery("SELECT COUNT(*) AS n FROM " + name + ";",
                    tech.ydb.table.transaction.TxControl.staleRo().setCommitTx(true),
                    tech.ydb.table.query.Params.empty(),
                    new tech.ydb.table.settings.ExecuteDataQuerySettings()).join().getValue();
            var rs = r.getResultSet(0);
            System.out.println("DDLPROBE count-after-truncate=" + (rs.next() ? rs.getColumn("n").getUint64() : -1));
            System.out.println("DDLPROBE drop-after-truncate -> "
                    + session.executeSchemeQuery("DROP TABLE " + name + ";").join().isSuccess());
        } catch (Exception e) {
            System.out.println("DDLPROBE truncate-setup threw " + String.valueOf(e.getMessage()).substring(0, 200));
        }
    }

    @Test
    void truncateIndexedTableWithIndex() {
        YdbSearchTestBase.ensureStarted();
        String prefix = "t_truncprobe";
        try {
            YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
        } catch (Exception e) {
            System.out.println("DDLPROBE ensure -> " + String.valueOf(e.getMessage()).substring(0, 150));
        }
        try (Session session = YdbSearchTestBase.session()) {
            session.executeSchemeQuery("TRUNCATE TABLE `" + prefix + "_vectors`;").join();
            System.out.println("DDLPROBE truncate-indexed -> true");
        } catch (Exception e) {
            System.out.println("DDLPROBE truncate-indexed threw "
                    + String.valueOf(e.getMessage()).substring(0, 200));
        }
    }

    @Test
    void dropIndexedTableActuallyVanishes() {
        YdbSearchTestBase.ensureStarted();
        String name = "gone_" + YdbSearchTestBase.randomPrefix();
        try (Session session = YdbSearchTestBase.session()) {
            session.executeSchemeQuery("CREATE TABLE `" + name + "` (a Utf8 NOT NULL,"
                    + " embedding String NOT NULL, PRIMARY KEY (a));").join();
            session.executeSchemeQuery("ALTER TABLE `" + name + "` ADD INDEX `v`"
                    + " GLOBAL USING vector_kmeans_tree ON (`embedding`)"
                    + " WITH (distance=cosine, vector_type=\"float\", vector_dimension=2);").join();
            System.out.println("DDLPROBE drop-idx -> "
                    + session.executeSchemeQuery("DROP TABLE `" + name + "`;").join().isSuccess());
            for (int i = 0; i < 6; i++) {
                try {
                    session.executeSchemeQuery("SELECT * FROM `" + name + "` LIMIT 0;").join();
                    System.out.println("DDLPROBE still-present iter=" + i);
                } catch (Exception e) {
                    System.out.println("DDLPROBE gone iter=" + i + " " + String.valueOf(e.getMessage()).substring(0, 120));
                    break;
                }
                try { Thread.sleep(5000); } catch (InterruptedException ex) { break; }
            }
        }
    }

    @Test
    void dropTableWithVectorIndex() {
        YdbSearchTestBase.ensureStarted();
        String name = "`dropv_" + YdbSearchTestBase.randomPrefix() + "`";
        try (Session session = YdbSearchTestBase.session()) {
            System.out.println("DDLPROBE mk -> " + session.executeSchemeQuery(
                    "CREATE TABLE " + name + " (a Utf8 NOT NULL, embedding String NOT NULL,"
                    + " PRIMARY KEY (a));").join().isSuccess());
            System.out.println("DDLPROBE mkidx -> " + session.executeSchemeQuery(
                    "ALTER TABLE " + name + " ADD INDEX `v` GLOBAL USING vector_kmeans_tree"
                    + " ON (`embedding`) WITH (distance=cosine);").join().isSuccess());
            System.out.println("DDLPROBE drop -> " + session.executeSchemeQuery(
                    "DROP TABLE " + name + ";").join());
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
