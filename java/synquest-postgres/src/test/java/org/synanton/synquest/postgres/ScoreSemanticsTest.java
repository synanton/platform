package org.synanton.synquest.postgres;

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
 * PG-POC-007-4 executable half: per-leg score semantics (the parameter-level
 * score-space axis) + tie ordering. Verdicts recorded in
 * {@code 007-4-fusion-parity.md}: lexical {@code ts_rank} ≥ 0 custom,
 * vector {@code -cosine_distance} ∈ [-2, 0], ties break by chunkId asc.
 * RRF-level parity is rank-based and lands with the hybrid leg (007-5).
 */
class ScoreSemanticsTest extends QuestPostgresFixture {

    // Local helpers (the contract's are protected to subclasses; this class
    // is a focused probe, not a second contract run — no duplication of
    // contract coverage, just construction).
    private static final TenantScope TENANT_A = TenantScope.of("tenant_a");
    private static final PolicyContext POLICY = PolicyContext.of("p", "r1");

    private static SecurityContext ctx() {
        return SecurityContext.user(TENANT_A, PrincipalRef.user("u-1"), POLICY);
    }

    private static EligibilityConstraints eligibility() {
        return EligibilityConstraints.from(TENANT_A, List.of(PrincipalRef.user("u-1")), POLICY);
    }

    private static ChunkProjection projection(String chunk, String text, long orderingKey, float[] embedding) {
        return new ChunkProjection(
                ChunkId.of(chunk),
                DocumentId.of("doc-" + chunk),
                "tenant_a",
                text,
                Map.of("type", "note"),
                embedding,
                EmbeddingModelRef.of("m", "v1", "d"),
                orderingKey,
                GenerationId.of("gen-1"));
    }

    @BeforeAll
    static void seed() throws Exception {
        ensureStarted();
        resetTenants("tenant_a");
        var engine = QuestPostgresFixture.newEngine();
        engine.upsert(
                        List.of(
                                projection("s1", "alpha beta gamma delta", 1, new float[] {1.0f, 0.0f}),
                                projection("s2", "alpha beta gamma delta", 2, new float[] {1.0f, 0.0f}),
                                projection("s3", "unrelated wording here", 3, new float[] {0.0f, 1.0f})))
                .toCompletableFuture()
                .join();
    }

    private static SearchResult lexical(String query) throws Exception {
        var engine = QuestPostgresFixture.newEngine();
        return engine.search(
                        ctx(),
                        new SearchRequest(
                                query,
                                Optional.empty(),
                                Optional.empty(),
                                SearchMode.LEXICAL,
                                eligibility(),
                                RelevanceFilters.none(),
                                TemporalExtension.empty(),
                                10,
                                0.0))
                .toCompletableFuture()
                .join();
    }

    private static SearchResult vector(float[] embedding) throws Exception {
        var engine = QuestPostgresFixture.newEngine();
        return engine.search(
                        ctx(),
                        new SearchRequest(
                                "ignored",
                                Optional.of(embedding),
                                Optional.empty(),
                                SearchMode.VECTOR,
                                eligibility(),
                                RelevanceFilters.none(),
                                TemporalExtension.empty(),
                                10,
                                -10.0))
                .toCompletableFuture()
                .join();
    }

    @Test
    void lexicalScoresAreFiniteAndHigherBetter() throws Exception {
        SearchResult result = lexical("alpha gamma");
        assertThat(result.hits()).hasSize(2);
        for (var hit : result.hits()) {
            assertThat(hit.score()).as("ts_rank finite, >= 0").isFinite().isGreaterThanOrEqualTo(0.0);
        }
    }

    @Test
    void identicalTextTiesBreakByChunkId() throws Exception {
        // s1 and s2 share text → identical ts_rank → chunkId asc decides.
        SearchResult result = lexical("alpha gamma");
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().get(0).chunkId().value()).isEqualTo("s1");
        assertThat(result.hits().get(1).chunkId().value()).isEqualTo("s2");
    }

    @Test
    void vectorScoresAreNegativeDistances() throws Exception {
        SearchResult result = vector(new float[] {1.0f, 0.0f});
        assertThat(result.hits()).isNotEmpty();
        for (var hit : result.hits()) {
            assertThat(hit.score())
                    .as("score = -cosine_distance, in [-2, 0]")
                    .isBetween(-2.0, 0.0);
        }
        assertThat(result.hits().get(0).chunkId().value()).isEqualTo("s1");
    }
}
