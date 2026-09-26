package org.synanton.synquest.ydb;

import org.junit.jupiter.api.Test;
import org.synanton.storage.testkit.PlanAssertions;
import tech.ydb.table.Session;
import tech.ydb.table.query.ExplainDataQueryResult;
import tech.ydb.table.settings.ExplainDataQuerySettings;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commit-2 usage assertions: vector retrieval must go through the ANN index
 * (plan references the index implementation tables), not a scan. Correctness
 * alone would pass on a full scan — this test pins the mechanism.
 */
class YdbVectorIndexUsageTest {

    @Test
    void vectorQueryUsesAnnIndex() {
        YdbSearchTestBase.ensureStarted();
        String prefix = YdbSearchTestBase.randomPrefix();
        YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
        try (Session session = YdbSearchTestBase.session()) {
            ExplainDataQueryResult plan =
                    session
                            .explainDataQuery(
                                    "DECLARE $t AS Utf8;"
                                            + "SELECT chunk_id FROM `"
                                            + prefix + "_vectors` VIEW `v_vec`"
                                            + " WHERE tenant_id=$t"
                                            + " ORDER BY Knn::CosineSimilarity(embedding,"
                                            + " Knn::ToBinaryStringFloat([CAST(1.0 AS Float),"
                                            + " CAST(0.0 AS Float)])) DESC LIMIT 10;",
                                    new ExplainDataQuerySettings())
                            .join()
                            .getValue();
            String ast = plan.getQueryAst() + "\n" + plan.getQueryPlan();
            System.out.println("INDEXPLAN " + ast.substring(0, Math.min(800, ast.length())));
            PlanAssertions.assertUsesIndexes(ast, "v_vec");
        }
    }

    @Test
    void fulltextQueryUsesIndex() {
        YdbSearchTestBase.ensureStarted();
        String prefix = YdbSearchTestBase.randomPrefix();
        YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
        try (Session session = YdbSearchTestBase.session()) {
            ExplainDataQueryResult plan =
                    session
                            .explainDataQuery(
                                    "DECLARE $t AS Utf8;"
                                            + "SELECT chunk_id, FulltextScore(chunk_text, \"probe\") AS relevance FROM `"
                                            + prefix + "_projections` VIEW `ft`"
                                            + " WHERE tenant_id=$t AND FulltextScore(chunk_text, \"probe\") > 0"
                                            + " ORDER BY relevance DESC LIMIT 10;",
                                    new ExplainDataQuerySettings())
                            .join()
                            .getValue();
            String ast = plan.getQueryAst() + "\n" + plan.getQueryPlan();
            PlanAssertions.assertUsesIndexes(ast, "ft");
        }
    }

    @Test
    void hybridQueryUsesBothIndexPaths() {
        YdbSearchTestBase.ensureStarted();
        String prefix = YdbSearchTestBase.randomPrefix();
        YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
        try (Session session = YdbSearchTestBase.session()) {
            ExplainDataQueryResult plan =
                    session
                            .explainDataQuery(
                                    "SELECT chunk_id FROM `" + prefix + "_vectors`"
                                            + " ORDER BY HybridRank(FulltextScore(chunk_text, \"probe\"),"
                                            + " Knn::CosineDistance(embedding,"
                                            + " Knn::ToBinaryStringFloat([CAST(1.0 AS Float),"
                                            + " CAST(0.0 AS Float)])),"
                                            + " (\"v_ft\", \"v_hyb\") AS Indexes)"
                                            + " LIMIT 10;",
                                    new ExplainDataQuerySettings())
                            .join()
                            .getValue();
            String ast = plan.getQueryAst() + "\n" + plan.getQueryPlan();
            PlanAssertions.assertUsesIndexes(ast, "v_ft", "v_hyb");
        }
    }
}
