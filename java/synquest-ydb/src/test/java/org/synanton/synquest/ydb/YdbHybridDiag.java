package org.synanton.synquest.ydb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tech.ydb.core.Status;
import tech.ydb.table.Session;
import tech.ydb.table.query.DataQueryResult;
import tech.ydb.table.query.Params;
import tech.ydb.table.settings.ExecuteDataQuerySettings;
import tech.ydb.table.transaction.TxControl;
import tech.ydb.table.values.PrimitiveValue;

/** Diagnostic: which HybridRank shapes resolve the FT branch. Gated, temporary. */
@EnabledIfSystemProperty(named = "ydb.probe", matches = "true")
class YdbHybridDiag {

    private static void attempt(Session session, String label, String yql) {
        try {
            DataQueryResult r =
                    session
                            .executeDataQuery(
                                    yql, TxControl.snapshotRo().setCommitTx(true), Params.empty(),
                                    new ExecuteDataQuerySettings())
                            .join()
                            .getValue();
            System.out.println("HYBDIAG " + label + " -> ok rows=" + r.getResultSet(0).getRowCount());
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            System.out.println("HYBDIAG " + label + " -> threw " + msg.substring(0, Math.min(300, msg.length())));
        }
    }

    @Test
    void diagnose() {
        YdbSearchTestBase.ensureStarted();
        String prefix = YdbSearchTestBase.randomPrefix();
        YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
        String table = "`" + prefix + "_vectors`";
        try (Session session = YdbSearchTestBase.session()) {
            session.executeSchemeQuery(
                            "UPSERT INTO " + table + " (key, tenant_id, chunk_id, doc_id, chunk_text,"
                                    + " metadata_json, embedding, ordering_key, generation) VALUES"
                                    + " ('k1', 'tenant_a', 'c1', 'd1', 'alpha migration plan', {},"
                                    + " Untag(Knn::ToBinaryStringFloat([CAST(1.0 AS Float), CAST(0.0 AS Float)]),"
                                    + " 'FloatVector'), 1, 'gen-1');")
                    .join();
            Thread.sleep(3000);
            String rank =
                    "HybridRank(FulltextScore(chunk_text, \"migration\"),"
                            + " Knn::CosineDistance(embedding,"
                            + " Knn::ToBinaryStringFloat([CAST(1.0 AS Float), CAST(0.0 AS Float)])),"
                            + " (\"v_ft\", \"v_hyb\") AS Indexes)";
            attempt(session, "no-where",
                    "SELECT chunk_id FROM " + table + " ORDER BY " + rank + " LIMIT 10;");
            attempt(session, "with-tenant-predicate",
                    "SELECT chunk_id FROM " + table + " WHERE tenant_id=\"tenant_a\""
                            + " ORDER BY " + rank + " LIMIT 10;");
            String rankExpr =
                    "HybridRank(FulltextScore(chunk_text, \"migration\"),"
                            + " Knn::CosineDistance(embedding,"
                            + " Knn::ToBinaryStringFloat([CAST(1.0 AS Float), CAST(0.0 AS Float)])),"
                            + " (\"v_ft\", \"v_hyb\") AS Indexes)";
            attempt(session, "select-alias-order-alias",
                    "SELECT chunk_id, " + rankExpr + " AS relevance FROM " + table
                            + " WHERE tenant_id=\"tenant_a\""
                            + " ORDER BY relevance DESC LIMIT 10;");
            attempt(session, "no-indexes-override",
                    "SELECT chunk_id FROM " + table + " WHERE tenant_id=\"tenant_a\""
                            + " ORDER BY HybridRank(FulltextScore(chunk_text, \"migration\"),"
                            + " Knn::CosineDistance(embedding,"
                            + " Knn::ToBinaryStringFloat([CAST(1.0 AS Float), CAST(0.0 AS Float)])))"
                            + " LIMIT 10;");
        } catch (Exception e) {
            System.out.println("HYBDIAG setup threw " + e.getMessage());
        }
    }
}
