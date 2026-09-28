package org.synanton.synquest.ydb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.synanton.synquest.api.TemporalExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pre-R3 fusion/pagination regression: filtered legs must page past
 * filtered-out rows (iterative over-fetch), not truncate at topK+1.
 * 30 chunks, metadata pred matching the odd half, topK=10 — the old
 * topK+1 fetch returned ~5; the paged path returns the full 10.
 */
class FilterPaginationTest {

    private static final GenerationId GEN = new GenerationId("page-gen-1");
    private static final EmbeddingModelRef MODEL = new EmbeddingModelRef("page", "v1", "page");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("benchmark", "page");
    private static final PolicyContext POLICY = new PolicyContext("benchmark", "v1");

    @Test
    void metadataFilteredLexicalPagesPastFilteredRows() throws Exception {
        YdbSearchTestBase.ensureStarted();
        String prefix = "t_quest_page";
        YdbSearchSchema.ensureSchema(YdbSearchTestBase.client(), prefix, 2);
        YdbSearchSchema.truncateAll(YdbSearchTestBase.client(), prefix);
        YdbSynquestEngine engine = new YdbSynquestEngine(YdbSearchTestBase.client(), prefix);

        List<ChunkProjection> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(
                    new ChunkProjection(
                            ChunkId.of("page-chunk-" + i),
                            DocumentId.of("page-doc"),
                            "tenant_07",
                            "common probe terms alpha",
                            Map.of("parity", i % 2 == 0 ? "even" : "odd"),
                            new float[] {i * 0.01f, 1.0f},
                            MODEL, i, GEN));
        }
        engine.upsert(rows).toCompletableFuture().join();

        TenantScope scope = TenantScope.of("tenant_07");
        SecurityContext context = SecurityContext.user(scope, PRINCIPAL, POLICY);
        SearchRequest request =
                new SearchRequest(
                        "common probe terms",
                        java.util.Optional.empty(),
                        Optional.of(MODEL),
                        SearchMode.LEXICAL,
                        EligibilityConstraints.from(scope, List.of(PRINCIPAL), POLICY),
                        new RelevanceFilters(Map.of("parity", "even")),
                        TemporalExtension.empty(),
                        10,
                        0.0);
        var result = engine.search(context, request).toCompletableFuture().join();
        assertThat(result.hits())
                .as("full top-10 from 15 eligible (old topK+1 path returned ~5)")
                .hasSize(10);
    }
}
