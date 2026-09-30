package org.synanton.synquest.postgres;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SearchResult;
import org.synanton.synquest.api.TemporalExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG-POC-007-6 metadata push-down guard (YDB P1-4 class, caught by
 * construction): the filter predicate lives in SQL, never in Java
 * post-fetch. Two halves per mode (lexical, vector; hybrid inherits both
 * legs): EXPLAIN shows {@code @>} in-plan, and a binding behavioral leg
 * proves pre-ranking effect — the fixture is selective (~5% eligible) with
 * ineligible rows ranked strictly higher, so post-fetch filtering would
 * return fewer than topK.
 *
 * <p>Cross-ref: the 007-5 probe's filter-application-point row — this guard
 * verifies PG's pre-retrieval side of that mechanism difference.
 */
class MetadataPushdownGuardTest extends QuestPostgresFixture {

    private static final String TENANT = "guard";
    private static final int TOTAL = 2000;
    private static final int ELIGIBLE = 100;

    private static final TenantScope SCOPE = TenantScope.of(TENANT);
    private static final PolicyContext POLICY = PolicyContext.of("p", "r1");

    private static SecurityContext ctx() {
        return SecurityContext.user(SCOPE, PrincipalRef.user("u-1"), POLICY);
    }

    private static EligibilityConstraints eligibility() {
        return EligibilityConstraints.from(SCOPE, List.of(PrincipalRef.user("u-1")), POLICY);
    }

    private static RelevanceFilters runbookOnly() {
        return new RelevanceFilters(Map.of("type", "runbook"));
    }

    @BeforeAll
    static void seed() throws Exception {
        ensureStarted();
        resetTenants(TENANT);
        try (Connection admin = QuestPostgresFixture.adminConnection();
                var ps = admin.prepareStatement("SELECT count(*) FROM chunks WHERE tenant_id = ?")) {
            ps.setString(1, TENANT);
            try (var rs = ps.executeQuery()) {
                rs.next();
                if (rs.getLong(1) >= TOTAL) {
                    return;
                }
            }
        }
        var engine = QuestPostgresFixture.newEngine();
        List<ChunkProjection> batch = new ArrayList<>(500);
        for (int i = 0; i < TOTAL; i++) {
            // Ineligible rows sort strictly higher: chunkIds "guard-a-*"
            // beat "guard-z-*" on ties, embeddings sit exactly on the query.
            boolean eligible = i >= TOTAL - ELIGIBLE;
            String chunk = String.format(eligible ? "guard-z-%04d" : "guard-a-%04d", i);
            float[] embedding = eligible ? new float[] {0.0f, 1.0f} : new float[] {1.0f, 0.0f};
            batch.add(
                    new ChunkProjection(
                            ChunkId.of(chunk),
                            DocumentId.of("doc-" + chunk),
                            TENANT,
                            "common filler alpha beta " + (eligible ? "runbook marker" : "note filler"),
                            Map.of("type", eligible ? "runbook" : "note"),
                            embedding,
                            EmbeddingModelRef.of("m", "v1", "d"),
                            i,
                            GenerationId.of("gen-guard")));
            if (batch.size() >= 500) {
                engine.upsert(batch).toCompletableFuture().join();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            engine.upsert(batch).toCompletableFuture().join();
        }
    }

    private static String explain(String sql, String tenant, String... params) throws Exception {
        // Explains exactly what the engine runs: same builder, same RLS scope.
        try (Connection app = QuestPostgresFixture.appConnection()) {
            app.setAutoCommit(false);
            try (var set = app.createStatement()) {
                set.execute("SET LOCAL app.tenant_id = '" + tenant + "'");
            }
            StringBuilder plan = new StringBuilder();
            try (var ps = app.prepareStatement("EXPLAIN " + sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setString(i + 1, params[i]);
                }
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
            }
            app.commit();
            return plan.toString();
        }
    }

    private static SearchRequest request(SearchMode mode, int topK, double minScore) {
        Optional<float[]> embedding =
                mode == SearchMode.LEXICAL ? Optional.empty() : Optional.of(new float[] {1.0f, 0.0f});
        return new SearchRequest(
                "common filler alpha",
                embedding,
                Optional.empty(),
                mode,
                eligibility(),
                runbookOnly(),
                TemporalExtension.empty(),
                topK,
                minScore);
    }

    @Test
    void lexicalPredicateIsInPlan() throws Exception {
        String plan =
                explain(
                        PostgresSynquestEngine.lexicalSql(true, 10),
                        TENANT,
                        "common | filler | alpha",
                        "common | filler | alpha",
                        "{\"type\":\"runbook\"}",
                        "gen-guard");
        assertThat(plan).as("lexical plan carries @> containment").contains("@>");
    }

    @Test
    void vectorPredicateIsInPlan() throws Exception {
        String plan =
                explain(
                        PostgresSynquestEngine.vectorSql(true, 10),
                        TENANT,
                        "[1,0,0,0]",
                        "{\"type\":\"runbook\"}",
                        "gen-guard");
        assertThat(plan).as("vector plan carries @> containment").contains("@>");
    }

    @Test
    void lexicalBindingFilterFillsTopK() throws Exception {
        SearchResult result =
                QuestPostgresFixture.newEngine()
                        .search(ctx(), request(SearchMode.LEXICAL, 10, 0.0))
                        .toCompletableFuture()
                        .join();
        assertThat(result.hits())
                .as("pre-ranking filter: topK filled from the 5% eligible")
                .hasSize(10);
        assertThat(result.hits())
                .allMatch(h -> h.chunkId().value().startsWith("guard-z-"));
    }

    @Test
    void vectorBindingFilterFillsTopK() throws Exception {
        SearchResult result =
                QuestPostgresFixture.newEngine()
                        .search(ctx(), request(SearchMode.VECTOR, 10, -10.0))
                        .toCompletableFuture()
                        .join();
        assertThat(result.hits())
                .as("pre-ranking filter: topK filled from the 5% eligible")
                .hasSize(10);
        assertThat(result.hits())
                .allMatch(h -> h.chunkId().value().startsWith("guard-z-"));
    }

    @Test
    void hybridBindingFilterFillsTopK() throws Exception {
        SearchResult result =
                QuestPostgresFixture.newEngine()
                        .search(ctx(), request(SearchMode.HYBRID, 10, 0.0))
                        .toCompletableFuture()
                        .join();
        assertThat(result.hits())
                .as("fused pre-filtered legs: topK filled, all eligible")
                .hasSize(10);
        assertThat(result.hits())
                .allMatch(h -> h.chunkId().value().startsWith("guard-z-"));
    }
}
