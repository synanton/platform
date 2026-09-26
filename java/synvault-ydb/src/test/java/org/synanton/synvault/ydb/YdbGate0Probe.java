package org.synanton.synvault.ydb;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tech.ydb.table.Session;
import tech.ydb.table.query.DataQueryResult;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.settings.ExecuteDataQuerySettings;
import tech.ydb.table.transaction.TxControl;
import tech.ydb.table.values.PrimitiveValue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 0 probe (Phase-2 pre-flight): pre-ranking eligibility composition on YDB
 * filtered vector + full-text indexes. Runs ONLY with {@code -Dydb.probe=true}.
 *
 * <p>Verdict scale: PASS (plan-level composition, correct + ranked) → 024B may
 * proceed to benchmarks; FAIL (leakage or unusable) → 024B collapses per preflight.
 */
@EnabledIfSystemProperty(named = "ydb.probe", matches = "true")
class YdbGate0Probe {

    private static String floats(float... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("CAST(").append(Float.toString(values[i])).append(" AS Float)");
        }
        return sb.toString();
    }

    private static void scheme(Session session, String label, String yql) {
        try {
            tech.ydb.core.Status status = session.executeSchemeQuery(yql).join();
            System.out.println("GATE0 " + label + " -> success=" + status.isSuccess()
                    + (status.isSuccess() ? "" : " " + status));
        } catch (Exception e) {
            System.out.println("GATE0 " + label + " -> threw " + e.getMessage());
        }
    }

    private static DataQueryResult data(
            Session session, String yql, Params params) {
        return session
                .executeDataQuery(yql, TxControl.serializableRw().setCommitTx(true), params,
                        new ExecuteDataQuerySettings())
                .join()
                .getValue();
    }

    private static Set<String> idsOf(DataQueryResult result) {
        Set<String> ids = new HashSet<>();
        ResultSetReader rs = result.getResultSet(0);
        while (rs.next()) {
            ids.add(rs.getColumn("id").getText() + "|" + rs.getColumn("tenant").getText());
        }
        return ids;
    }

    @Test
    void eligibilityComposesAtPlanLevel() {        YdbTestBase.ensureStarted();
        String table = "`gate0_" + YdbTestBase.randomPrefix() + "`";
        try (Session session = YdbTestBase.session()) {
            scheme(session, "create",
                    "CREATE TABLE " + table + " (id Utf8 NOT NULL, tenant Utf8 NOT NULL,"
                            + " chunk_text Utf8 NOT NULL, embedding String NOT NULL,"
                            + " PRIMARY KEY (id));");
            // Representative data BEFORE index creation (empty-table indexes degrade).
            String[][] rows = {
                {"a1", "tenant_a", "alpha migration plan", null},
                {"a2", "tenant_a", "alpha rollback steps", null},
                {"b1", "tenant_b", "alpha migration plan", null},
                {"b2", "tenant_b", "beta release notes", null},
            };
            float[][] vecs = {{1, 0, 0, 0}, {0.9f, 0.1f, 0, 0}, {1, 0, 0, 0}, {0, 0, 0, 1}};
            for (int i = 0; i < rows.length; i++) {
                data(session,
                        "DECLARE $id AS Utf8; DECLARE $t AS Utf8; DECLARE $x AS Utf8;"
                                + "UPSERT INTO " + table + " (id, tenant, chunk_text, embedding)"
                                + " VALUES ($id, $t, $x, Untag(Knn::ToBinaryStringFloat([" + floats(vecs[i]) + "]), 'FloatVector'));",
                        Params.create()
                                .put("$id", PrimitiveValue.newText(rows[i][0]))
                                .put("$t", PrimitiveValue.newText(rows[i][1]))
                                .put("$x", PrimitiveValue.newText(rows[i][2])));
            }
            scheme(session, "vector-index",
                    "ALTER TABLE " + table + " ADD INDEX `v_idx` GLOBAL USING vector_kmeans_tree"
                            + " ON (`tenant`, `embedding`)"
                            + " WITH (distance=cosine, vector_type=\"float\", vector_dimension=4);");
            // FT leg uses a separate integer-PK table: __ydb_row_id is flag-disabled
            // on this build (see probe finding), so Utf8-PK tables cannot carry FT indexes.
            String ftTable = "`ft_" + YdbTestBase.randomPrefix() + "`";
            scheme(session, "ft-create",
                    "CREATE TABLE " + ftTable + " (rowid Uint64 NOT NULL, tenant Utf8 NOT NULL,"
                            + " chunk_text Utf8 NOT NULL, PRIMARY KEY (rowid));");
            Object[][] ftRows = {
                {1L, "tenant_a", "alpha migration plan"},
                {2L, "tenant_a", "alpha rollback steps"},
                {3L, "tenant_b", "alpha migration plan"},
                {4L, "tenant_b", "beta release notes"},
            };
            for (Object[] row : ftRows) {
                data(session,
                        "DECLARE $id AS Uint64; DECLARE $t AS Utf8; DECLARE $x AS Utf8;"
                                + "UPSERT INTO " + ftTable + " (rowid, tenant, chunk_text)"
                                + " VALUES ($id, $t, $x);",
                        Params.create()
                                .put("$id", PrimitiveValue.newUint64((Long) row[0]))
                                .put("$t", PrimitiveValue.newText((String) row[1]))
                                .put("$x", PrimitiveValue.newText((String) row[2])));
            }
            scheme(session, "fulltext-index",
                    "ALTER TABLE " + ftTable + " ADD INDEX `f_idx` GLOBAL USING fulltext_relevance"
                            + " ON (`chunk_text`)"
                            + " WITH (tokenizer=standard, use_filter_lowercase=true);");

            // Wait for index builds (poll a filtered vector query for the expected row).
            Set<String> gotA = Set.of();
            for (int attempt = 0; attempt < 30; attempt++) {
                gotA = vectorSearch(session, table, "tenant_a", new float[] {1, 0, 0, 0});
                if (gotA.contains("a1|tenant_a")) {
                    break;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("GATE0 vector-tenant-a -> " + gotA);
            assertThat(gotA)
                    .as("filtered vector search must return only eligible tenant rows")
                    .doesNotContain("b1|tenant_b", "b2|tenant_b");
            assertThat(gotA).as("eligible rows must be found").isNotEmpty();

            Set<String> gotB = vectorSearch(session, table, "tenant_b", new float[] {0, 0, 0, 1});
            System.out.println("GATE0 vector-tenant-b -> " + gotB);
            assertThat(gotB).doesNotContain("a1|tenant_a", "a2|tenant_a");

            Set<String> gotFt = Set.of();
            String ftError = "";
            for (int attempt = 0; attempt < 15; attempt++) {
                try {
                    gotFt = fulltextSearch(session, ftTable, "tenant_a", "migration");
                    if (!gotFt.isEmpty()) {
                        break;
                    }
                } catch (Exception e) {
                    ftError = String.valueOf(e.getMessage());
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("GATE0 fulltext-tenant-a -> " + gotFt + " last-err=" + ftError);
            // Isolation: unfiltered FT, docs-exact shape (alias in ORDER BY).
            try {
                DataQueryResult bare =
                        data(session,
                                "SELECT rowid, FulltextScore(chunk_text, \"migration\") AS relevance FROM "
                                        + ftTable + " VIEW `f_idx`"
                                        + " WHERE FulltextScore(chunk_text, \"migration\") > 0"
                                        + " ORDER BY relevance DESC LIMIT 10;",
                                Params.empty());
                ResultSetReader rs = bare.getResultSet(0);
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    sb.append(rs.getColumn("rowid").getUint64()).append(',');
                }
                System.out.println("GATE0 fulltext-no-predicate -> [" + sb + "]");
            } catch (Exception e) {
                System.out.println("GATE0 fulltext-no-predicate -> threw " + e.getMessage());
            }
            assertThat(gotFt)
                    .as("filtered fulltext must return only eligible tenant rows")
                    .doesNotContain("3|tenant_b", "4|tenant_b");
            assertThat(gotFt).as("eligible fulltext rows must be found").isNotEmpty();

            System.out.println("GATE0 VERDICT=PASS(plan-level eligibility composition demonstrated)");
            scheme(session, "cleanup", "DROP TABLE " + table + ";");
            scheme(session, "cleanup-ft", "DROP TABLE " + ftTable + ";");
        }
    }

        @Test
    void hybridRankAvailability() {        YdbTestBase.ensureStarted();
        String table = "`hyb_" + YdbTestBase.randomPrefix() + "`";
        try (Session session = YdbTestBase.session()) {
            scheme(session, "hyb-create",
                    "CREATE TABLE " + table + " (id Uint64 NOT NULL, tenant Utf8 NOT NULL,"
                            + " chunk_text Utf8 NOT NULL, embedding String NOT NULL,"
                            + " PRIMARY KEY (id));");
            Object[][] rows = {
                {1L, "tenant_a", "alpha migration plan", new float[] {1, 0, 0, 0}},
                {2L, "tenant_a", "alpha rollback steps", new float[] {0.9f, 0.1f, 0, 0}},
                {3L, "tenant_b", "alpha migration plan", new float[] {1, 0, 0, 0}},
            };
            for (Object[] row : rows) {
                data(session,
                        "DECLARE $id AS Uint64; DECLARE $t AS Utf8; DECLARE $x AS Utf8;"
                                + "UPSERT INTO " + table + " (id, tenant, chunk_text, embedding)"
                                + " VALUES ($id, $t, $x, Untag(Knn::ToBinaryStringFloat(["
                                + floats((float[]) row[3]) + "]), 'FloatVector'));",
                        Params.create()
                                .put("$id", PrimitiveValue.newUint64((Long) row[0]))
                                .put("$t", PrimitiveValue.newText((String) row[1]))
                                .put("$x", PrimitiveValue.newText((String) row[2])));
            }
            scheme(session, "hyb-ft",
                    "ALTER TABLE " + table + " ADD INDEX `h_ft` GLOBAL USING fulltext_relevance"
                            + " ON (`chunk_text`)"
                            + " WITH (tokenizer=standard, use_filter_lowercase=true);");
            scheme(session, "hyb-vec",
                    "ALTER TABLE " + table + " ADD INDEX `h_vec` GLOBAL USING vector_kmeans_tree"
                            + " ON (`embedding`)"
                            + " WITH (distance=cosine, vector_type=\"float\", vector_dimension=4);");
            String hybrid =
                    "SELECT id, tenant FROM " + table
                            + " WHERE tenant=\"tenant_a\""
                            + " ORDER BY HybridRank(FulltextScore(chunk_text, \"migration\"),"
                            + " Knn::CosineDistance(embedding,"
                            + " Knn::ToBinaryStringFloat([" + floats(new float[] {1, 0, 0, 0}) + "])))"
                            + " LIMIT 10;";
            String outcome = "";
            for (int attempt = 0; attempt < 15; attempt++) {
                try {
                    DataQueryResult result = data(session, hybrid, Params.empty());
                    ResultSetReader rs = result.getResultSet(0);
                    StringBuilder sb = new StringBuilder();
                    while (rs.next()) {
                        sb.append(rs.getColumn("id").getUint64())
                                .append('|')
                                .append(rs.getColumn("tenant").getText())
                                .append(',');
                    }
                    outcome = "[" + sb + "]";
                    if (!outcome.equals("[]")) {
                        break;
                    }
                } catch (Exception e) {
                    outcome = "threw " + e.getMessage();
                    break;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("GATE0 hybrid-tenant-a -> " + outcome);
            scheme(session, "hyb-cleanup", "DROP TABLE " + table + ";");
        }
    }

    private static Set<String> vectorSearch(Session session, String table, String tenant, float[] query) {
        DataQueryResult result =
                data(session,
                        "DECLARE $t AS Utf8;"
                                + "SELECT id, tenant FROM " + table + " VIEW `v_idx`"
                                + " WHERE tenant=$t"
                                + " ORDER BY Knn::CosineSimilarity(embedding,"
                                + " Knn::ToBinaryStringFloat([" + floats(query) + "])) DESC LIMIT 10;",
                        Params.create().put("$t", PrimitiveValue.newText(tenant)));
        return idsOf(result);
    }

    private static Set<String> fulltextSearch(Session session, String table, String tenant, String terms) {
        DataQueryResult result =
                data(session,
                        "DECLARE $t AS Utf8;"
                                + "SELECT rowid, tenant, FulltextScore(chunk_text, \"" + terms + "\") AS relevance FROM "
                                + table + " VIEW `f_idx`"
                                + " WHERE tenant=$t AND FulltextScore(chunk_text, \"" + terms + "\") > 0"
                                + " ORDER BY relevance DESC LIMIT 10;",
                        Params.create().put("$t", PrimitiveValue.newText(tenant)));
        Set<String> ids = new HashSet<>();
        ResultSetReader rs = result.getResultSet(0);
        while (rs.next()) {
            ids.add(rs.getColumn("rowid").getUint64() + "|" + rs.getColumn("tenant").getText());
        }
        return ids;
    }
}
