package org.synanton.bench.baseline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.synanton.bench.baseline.BaselineIndexer.ChunkRow;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.service.HybridSearcher;
import org.synanton.synquest.service.RrfFusion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 028b.3 close-gate: wrapped baseline produces identical top-K ids and scores
 * to the manual production pipeline (HybridSearcher + RrfFusion, BaselineBench
 * shape) on a mini two-tenant index. Routing without behavioral equivalence
 * would let baseline-v1.json measure a lookalike — this gate prevents that.
 */
class BaselineEquivalenceTest {

    private static final PrincipalRef PRINCIPAL = new PrincipalRef("benchmark", "028b");
    private static final PolicyContext POLICY = new PolicyContext("benchmark", "v1");

    private static String vecB64(float... vec) {
        java.nio.ByteBuffer buf =
                java.nio.ByteBuffer.allocate(vec.length * 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) {
            buf.putFloat(v);
        }
        return java.util.Base64.getEncoder().encodeToString(buf.array());
    }

    private static List<ChunkRow> miniRows() {
        return List.of(
                new ChunkRow("e1", "tenant_07", "alpha beta gamma", vecB64(1, 0),
                        Map.of("doc_type", "memo")),
                new ChunkRow("e2", "tenant_07", "beta gamma delta", vecB64(0, 1),
                        Map.of("doc_type", "report")),
                new ChunkRow("e3", "tenant_11", "gamma delta alpha", vecB64(1, 1),
                        Map.of("doc_type", "memo")),
                new ChunkRow("e4", "tenant_11", "delta alpha beta", vecB64(0, 0.5f),
                        Map.of("doc_type", "report")));
    }

    private SecurityContext context(String tenant) {
        return SecurityContext.user(TenantScope.of(tenant), PRINCIPAL, POLICY);
    }

    @Test
    void wrapperMatchesManualPipeline() throws Exception {
        Path root = Files.createTempDirectory("baseline-equiv");
        BaselineIndexer.BuildStats stats =
                BaselineIndexer.build(() -> miniRows(), root);
        assertThat(stats.chunks()).isEqualTo(4);
        assertThat(stats.tenants()).isEqualTo(2);

        TenantScope tenant = TenantScope.of("tenant_07");
        try (HybridSearcher raw = new HybridSearcher(root.resolve("tenant-tenant_07"), 2)) {
            BaselineSynquestEngine engine =
                    new BaselineSynquestEngine(
                            new BaselineIndex.TenantIndex(Map.of("tenant_07", raw)));

            // Manual production pipeline (BaselineBench shape): full fusion,
            // filter-after-fuse (same as wrapper — pre-truncated fusion would
            // hide filtered attributes past rank 10).
            var lex = raw.lexical("alpha", 100);
            var dense = raw.dense(new float[] {1, 0}, 100);
            var fused = RrfFusion.combine(dense, lex, Integer.MAX_VALUE, 60);
            var stored = raw.storedFields();
            List<String> manualIds = new java.util.ArrayList<>();
            List<Double> manualScores = new java.util.ArrayList<>();
            for (var f : fused) {
                manualIds.add(stored.document(f.docId()).get("id"));
                manualScores.add(f.rrfScore());
            }

            // Wrapper hybrid over the same index.
            var result =
                    engine.search(
                                    context("tenant_07"),
                                    BaselineSynquestEngine.requestFor(
                                            "alpha", Optional.of(new float[] {1, 0}),
                                            SearchMode.HYBRID, tenant, Map.of(), 10))
                            .toCompletableFuture()
                            .join();
            // tenant_07 holds e1/e2 only (structural isolation); wrapper must
            // return exactly the manual pipeline's top-K in order, same scores.
            assertThat(result.hits().stream().map(h -> h.chunkId().value()).toList())
                    .containsExactlyElementsOf(manualIds.subList(0, result.hits().size()));
            for (int i = 0; i < result.hits().size(); i++) {
                assertThat(result.hits().get(i).score())
                        .as("score identical at rank " + i)
                        .isEqualTo(manualScores.get(i));
            }
            // No cross-tenant leakage at build: tenant_11 rows absent.
            assertThat(result.hits().stream().map(h -> h.chunkId().value()).toList())
                    .doesNotContain("e3", "e4");
        }
    }
}
