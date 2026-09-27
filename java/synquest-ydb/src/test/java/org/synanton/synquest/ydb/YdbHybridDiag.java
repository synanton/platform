package org.synanton.synquest.ydb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tech.ydb.core.Status;
import tech.ydb.table.Session;
import tech.ydb.table.query.DataQueryResult;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.query.Params;
import tech.ydb.table.settings.ExecuteDataQuerySettings;
import tech.ydb.table.transaction.TxControl;
import tech.ydb.table.values.PrimitiveValue;
import org.junit.jupiter.api.AfterAll;

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
        YdbSearchTestBase.trackedSchema(prefix, 2);
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

    @Test
    void generationPredicateThroughVectorView() {
        YdbSearchTestBase.ensureStarted();
        String prefix = YdbSearchTestBase.randomPrefix();
        YdbSearchTestBase.trackedSchema(prefix, 2);
        String vectors = "`" + prefix + "_vectors`";
        try (Session session = YdbSearchTestBase.session()) {
            exec(session, "UPSERT INTO " + vectors + " (key, tenant_id, chunk_id, doc_id,"
                    + " chunk_text, metadata_json, embedding, ordering_key, generation) VALUES"
                    + " ('k1', 'tenant_a', 'c1', 'd1', 'probe text', {},"
                    + " Untag(Knn::ToBinaryStringFloat([CAST(1.0 AS Float), CAST(0.0 AS Float)]),"
                    + " 'FloatVector'), 1, 'gen-1');");
            query(session, "gen-predicate",
                    "SELECT chunk_id FROM " + vectors + " VIEW `v_vec`"
                            + " WHERE tenant_id='tenant_a' AND generation='gen-1'"
                            + " ORDER BY Knn::CosineSimilarity(embedding,"
                            + " Knn::ToBinaryStringFloat([CAST(1.0 AS Float), CAST(0.0 AS Float)]))"
                            + " DESC LIMIT 10;");
            query(session, "gen-select",
                    "SELECT generation FROM " + vectors + " VIEW `v_vec`"
                            + " WHERE tenant_id='tenant_a'"
                            + " ORDER BY Knn::CosineSimilarity(embedding,"
                            + " Knn::ToBinaryStringFloat([CAST(1.0 AS Float), CAST(0.0 AS Float)]))"
                            + " DESC LIMIT 10;");
        }
    }

    private static void exec(Session session, String yql) {
        tech.ydb.core.Status s = session.executeSchemeQuery(yql).join();
        System.out.println("HYBDIAG exec -> " + s.isSuccess() + " " + s);
    }

    private static void query(Session session, String label, String yql) {
        try {
            var r = session.executeDataQuery(yql,
                    tech.ydb.table.transaction.TxControl.snapshotRo().setCommitTx(true),
                    tech.ydb.table.query.Params.empty(),
                    new tech.ydb.table.settings.ExecuteDataQuerySettings()).join().getValue();
            System.out.println("HYBDIAG " + label + " -> ok rows=" + r.getResultSet(0).getRowCount());
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            System.out.println("HYBDIAG " + label + " -> threw " + m.substring(0, Math.min(280, m.length())));
        }
    }

    @Test
    void promotionSequenceProbe() throws Exception {
        YdbSearchTestBase.ensureStarted();
        String prefix = YdbSearchTestBase.randomPrefix();
        YdbSearchTestBase.trackedSchema(prefix, 2);
        YdbSynquestEngine engine = new YdbSynquestEngine(YdbSearchTestBase.client(), prefix);
        var admin = (org.synanton.synquest.api.SynquestIndexAdmin) engine;
        var g1 = new org.synanton.storage.contract.GenerationId("gen-promote-1");
        var g2 = new org.synanton.storage.contract.GenerationId("gen-promote-2");
        engine.upsert(java.util.List.of(
                new org.synanton.synquest.api.ChunkProjection(
                        org.synanton.storage.contract.ChunkId.of("c1"),
                        org.synanton.storage.contract.DocumentId.of("doc-c1"),
                        "tenant_a", "genone harvest moon", java.util.Map.of(), null,
                        org.synanton.storage.contract.EmbeddingModelRef.of("m", "v1", "d"),
                        1, g1))).toCompletableFuture().join();
        System.out.println("HYBDIAG wrote-g1");
        admin.rebuild(new org.synanton.synquest.api.RebuildOptions(g2, false))
                .toCompletableFuture().join();
        System.out.println("HYBDIAG rebuilt-g2");
        engine.upsert(java.util.List.of(
                new org.synanton.synquest.api.ChunkProjection(
                        org.synanton.storage.contract.ChunkId.of("c3"),
                        org.synanton.storage.contract.DocumentId.of("doc-c3"),
                        "tenant_a", "gentwo solar wind", java.util.Map.of(), null,
                        org.synanton.storage.contract.EmbeddingModelRef.of("m", "v1", "d"),
                        3, g2))).toCompletableFuture().join();
        System.out.println("HYBDIAG wrote-g2");
        try (Session session = YdbSearchTestBase.session()) {
            String pt = "`" + prefix + "_projections`";
            DataQueryResult all =
                    session.executeDataQuery("SELECT chunk_id, generation FROM " + pt + ";",
                            TxControl.snapshotRo().setCommitTx(true), Params.empty(),
                            new ExecuteDataQuerySettings()).join().getValue();
            ResultSetReader rs = all.getResultSet(0);
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                sb.append(rs.getColumn("chunk_id").getText()).append('=')
                        .append(rs.getColumn("generation").getText()).append(';');
            }
            System.out.println("HYBDIAG table-contents -> [" + sb + "]");
            try (Session s2 = YdbSearchTestBase.session()) {
                System.out.println("HYBDIAG drop-ft -> " + s2.executeSchemeQuery(
                        "ALTER TABLE " + pt + " DROP INDEX `ft`;").join());
                s2.executeSchemeQuery("ALTER TABLE " + pt + " ADD INDEX `ft` GLOBAL USING fulltext_relevance"
                        + " ON (`chunk_text`)"
                        + " WITH (tokenizer=standard, use_filter_lowercase=true);").join();
                System.out.println("HYBDIAG recreated-ft");
                Thread.sleep(5000);
            }
            DataQueryResult ft =
                    session.executeDataQuery(
                            "DECLARE $t AS Utf8; DECLARE $g AS Utf8;"
                                    + "SELECT chunk_id, FulltextScore(chunk_text, \"solar wind\") AS relevance FROM "
                                    + pt + " VIEW `ft`"
                                    + " WHERE tenant_id=$t AND generation=$g"
                                    + " AND FulltextScore(chunk_text, \"solar wind\") > 0"
                                    + " ORDER BY relevance DESC LIMIT 10;",
                            TxControl.snapshotRo().setCommitTx(true),
                            Params.create()
                                    .put("$t", PrimitiveValue.newText("tenant_a"))
                                    .put("$g", PrimitiveValue.newText("gen-promote-2")),
                            new ExecuteDataQuerySettings()).join().getValue();
            ResultSetReader rs2 = ft.getResultSet(0);
            StringBuilder sb2 = new StringBuilder();
            while (rs2.next()) {
                sb2.append(rs2.getColumn("chunk_id").getText()).append(',');
            }
            System.out.println("HYBDIAG ft-nopredicate -> [" + sb2 + "]");
        }
        for (String qt : new String[] {"solar wind", "wind solar", "solar wind harvest",
                "harvest moon solar wind", "harvest", "gentwo"}) {
        for (int i = 0; i < 2; i++) {
            final String queryText = qt;
            final int round = i;
            var r = engine.search(
                    org.synanton.storage.contract.SecurityContext.user(
                            org.synanton.storage.contract.TenantScope.of("tenant_a"),
                            org.synanton.storage.contract.PrincipalRef.user("u-1"),
                            org.synanton.storage.contract.PolicyContext.of("p", "r1")),
                    new org.synanton.synquest.api.SearchRequest(
                            queryText, java.util.Optional.empty(), java.util.Optional.empty(),
                            org.synanton.synquest.api.SearchMode.LEXICAL,
                            org.synanton.synquest.api.EligibilityConstraints.from(
                                    org.synanton.storage.contract.TenantScope.of("tenant_a"),
                                    java.util.List.of(org.synanton.storage.contract.PrincipalRef.user("u-1")),
                                    org.synanton.storage.contract.PolicyContext.of("p", "r1")),
                            org.synanton.synquest.api.RelevanceFilters.none(),
                            org.synanton.synquest.api.TemporalExtension.empty(), 10, 0.0))
                    .toCompletableFuture().join();
            System.out.println("HYBDIAG search-" + qt + "-" + round + " hits=" + r.hits().size()
                    + " " + r.hits().stream().map(h -> h.text()).toList());
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
        }
    }

    @AfterAll
    static void dropSchemas() {
        YdbSearchTestBase.dropAllTracked();
    }
}
