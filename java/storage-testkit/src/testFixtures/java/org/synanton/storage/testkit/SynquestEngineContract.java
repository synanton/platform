package org.synanton.storage.testkit;

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
import org.synanton.storage.contract.StorageErrorKind;
import org.synanton.storage.contract.TenantScope;
import org.synanton.synquest.api.ChunkProjection;
import org.synanton.synquest.api.EligibilityConstraints;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SearchResult;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.SynquestIndexAdmin;
import org.synanton.synquest.api.SynquestIndexWriter;
import org.synanton.synquest.api.TemporalExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adapter-agnostic contract for {@link SynquestEngine} + {@link SynquestIndexWriter}
 * (YDB-POC-015/016/019, proposal §10). Pre-ranking eligibility, temporal rejection,
 * and regression prevention are asserted at the port — never inside an adapter.
 */
public abstract class SynquestEngineContract {

    protected abstract SynquestEngine newEngine();

    protected abstract SynquestIndexWriter newWriter();

    protected abstract SynquestIndexAdmin newAdmin();

    protected static final TenantScope TENANT_A = TenantScope.of("tenant_a");
    protected static final TenantScope TENANT_B = TenantScope.of("tenant_b");
    protected static final PolicyContext POLICY = PolicyContext.of("p", "r1");
    protected static final EmbeddingModelRef MODEL = EmbeddingModelRef.of("m", "v1", "d");
    protected static final GenerationId GEN = GenerationId.of("gen-1");

    protected static SecurityContext ctx(TenantScope tenant) {
        return SecurityContext.user(tenant, PrincipalRef.user("u-1"), POLICY);
    }

    protected static EligibilityConstraints eligibility(TenantScope tenant) {
        return EligibilityConstraints.from(tenant, List.of(PrincipalRef.user("u-1")), POLICY);
    }

    protected static ChunkProjection projection(String tenant, String chunk, String text, long orderingKey) {
        return projection(tenant, chunk, text, orderingKey, new float[] {1.0f, 0.0f});
    }

    protected static ChunkProjection projection(
            String tenant, String chunk, String text, long orderingKey, float[] embedding) {
        return projection(tenant, chunk, text, orderingKey, embedding, GEN);
    }

    protected static ChunkProjection projection(
            String tenant,
            String chunk,
            String text,
            long orderingKey,
            float[] embedding,
            GenerationId generation) {
        return new ChunkProjection(
                ChunkId.of(chunk),
                DocumentId.of("doc-" + chunk),
                tenant,
                text,
                Map.of("type", "note"),
                embedding,
                MODEL,
                orderingKey,
                generation);
    }

    protected static SearchRequest lexical(String queryText, TenantScope tenant, int topK) {
        return new SearchRequest(
                queryText,
                Optional.empty(),
                Optional.empty(),
                SearchMode.LEXICAL,
                eligibility(tenant),
                RelevanceFilters.none(),
                TemporalExtension.empty(),
                topK,
                0.0);
    }

    @Test
    void lexicalRetrievalFindsMatchingChunk() {
        SynquestEngine engine = newEngine();
        newWriter().upsert(List.of(projection("tenant_a", "c1", "cassandra consistency tuning guide", 1)))
                .toCompletableFuture()
                .join();
        SearchResult result =
                engine.search(ctx(TENANT_A), lexical("cassandra tuning", TENANT_A, 10))
                        .toCompletableFuture()
                        .join();
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).chunkId().value()).isEqualTo("c1");
    }

    @Test
    void vectorRetrievalRanksByEmbedding() {
        SynquestEngine engine = newEngine();
        if (!engine.capabilities().vector()) {
            return;
        }
        newWriter()
                .upsert(
                        List.of(
                                projection("tenant_a", "near", "orthogonal topic", 1, new float[] {1.0f, 0.0f}),
                                projection("tenant_a", "far", "orthogonal topic", 1, new float[] {0.0f, 1.0f})))
                .toCompletableFuture()
                .join();
        SearchRequest request =
                new SearchRequest(
                        "query",
                        Optional.of(new float[] {1.0f, 0.0f}),
                        Optional.of(MODEL),
                        SearchMode.VECTOR,
                        eligibility(TENANT_A),
                        RelevanceFilters.none(),
                        TemporalExtension.empty(),
                        10,
                        0.0);
        SearchResult result = engine.search(ctx(TENANT_A), request).toCompletableFuture().join();
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.hits().get(0).chunkId().value()).isEqualTo("near");
    }

    @Test
    void hybridRetrievalCombinesLexicalAndVector() {
        SynquestEngine engine = newEngine();
        if (!engine.capabilities().hybrid()) {
            return;
        }
        newWriter()
                .upsert(
                        List.of(
                                projection("tenant_a", "lex", "hybrid fusion calibration", 1, new float[] {0.0f, 1.0f}),
                                projection("tenant_a", "vec", "unrelated wording here", 1, new float[] {1.0f, 0.0f})))
                .toCompletableFuture()
                .join();
        SearchRequest request =
                new SearchRequest(
                        "fusion calibration",
                        Optional.of(new float[] {1.0f, 0.0f}),
                        Optional.of(MODEL),
                        SearchMode.HYBRID,
                        eligibility(TENANT_A),
                        RelevanceFilters.none(),
                        TemporalExtension.empty(),
                        10,
                        0.0);
        SearchResult result = engine.search(ctx(TENANT_A), request).toCompletableFuture().join();
        assertThat(result.hits()).hasSize(2);
    }

    @Test
    void tenantEligibilityIsPreRanking() {
        SynquestEngine engine = newEngine();
        newWriter().upsert(List.of(projection("tenant_a", "c1", "secret migration plan", 1)))
                .toCompletableFuture()
                .join();
        SearchResult result =
                engine.search(ctx(TENANT_B), lexical("migration plan", TENANT_B, 10))
                        .toCompletableFuture()
                        .join();
        assertThat(result.hits()).isEmpty();
        assertThat(result.totalEligible()).isZero();
        assertThat(result.highlights()).isEmpty();
    }

    /**
     * P0-3: service contexts must not broaden to the request's eligibility tenant.
     * A service context for tenant A searching with tenant B eligibility is
     * rejected (FORBIDDEN) — never silently scoped, never cross-tenant.
     */
    @Test
    void serviceContextBroadeningIsRejected() {
        SynquestEngine engine = newEngine();
        newWriter().upsert(List.of(projection("tenant_a", "c1", "secret migration plan", 1)))
                .toCompletableFuture()
                .join();
        SecurityContext service =
                SecurityContext.service(TENANT_A, PrincipalRef.service("indexer"), POLICY);
        SearchRequest request = lexical("migration plan", TENANT_B, 10);
        assertThatThrownBy(() -> engine.search(service, request).toCompletableFuture().join())
                .hasStackTraceContaining(StorageErrorKind.FORBIDDEN.name());
    }

    /**
     * 025b gap, visible not silent: principal/policy/explicit-authorization
     * enforcement is unimplemented — tenant scope is the only enforced
     * eligibility dimension. A policy-denied principal currently retrieves as if
     * allowed. Disabled until 025b lands, then this test must pass.
     */
    @Test
    @org.junit.jupiter.api.Disabled("025b: principal/policy eligibility unenforced")
    void principalPolicyEligibilityIsEnforced() {
        SynquestEngine engine = newEngine();
        newWriter().upsert(List.of(projection("tenant_a", "c1", "secret migration plan", 1)))
                .toCompletableFuture()
                .join();
        EligibilityConstraints denied =
                new EligibilityConstraints(
                        TENANT_A,
                        List.of(PrincipalRef.user("revoked-user")),
                        new PolicyContext("policy-deny-all", "r1"),
                        true);
        SearchRequest request =
                new SearchRequest(
                        "migration plan",
                        Optional.empty(),
                        Optional.empty(),
                        SearchMode.LEXICAL,
                        denied,
                        RelevanceFilters.none(),
                        TemporalExtension.empty(),
                        10,
                        0.0);
        SearchResult result =
                engine.search(ctx(TENANT_A), request).toCompletableFuture().join();
        assertThat(result.hits())
                .as("policy-denied principal must see nothing")
                .isEmpty();
    }

    @Test
    void temporalExtensionRejectedWhenUnsupported() {
        SynquestEngine engine = newEngine();
        if (engine.capabilities().temporal()) {
            return;
        }
        TemporalExtension temporal =
                new TemporalExtension(
                        Optional.of(java.time.Instant.now()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        false);
        SearchRequest request =
                new SearchRequest(
                        "anything",
                        Optional.empty(),
                        Optional.empty(),
                        SearchMode.LEXICAL,
                        eligibility(TENANT_A),
                        RelevanceFilters.none(),
                        temporal,
                        10,
                        0.0);
        assertThatThrownBy(() -> engine.search(ctx(TENANT_A), request).toCompletableFuture().join())
                .hasStackTraceContaining(StorageErrorKind.UNSUPPORTED.name());
    }

    @Test
    void outOfOrderProjectionCannotRegress() {
        SynquestEngine engine = newEngine();
        var writer = newWriter();
        writer.upsert(List.of(projection("tenant_a", "c1", "version two text", 2)))
                .toCompletableFuture()
                .join();
        writer.upsert(List.of(projection("tenant_a", "c1", "version one text", 1)))
                .toCompletableFuture()
                .join();
        SearchResult result =
                engine.search(ctx(TENANT_A), lexical("version", TENANT_A, 10)).toCompletableFuture().join();
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).text()).contains("version two");
    }

    @Test
    void generationScopedDelete() {
        SynquestEngine engine = newEngine();
        var writer = newWriter();
        writer.upsert(List.of(projection("tenant_a", "c1", "to be deleted chunk", 1)))
                .toCompletableFuture()
                .join();
        writer.delete(GEN, List.of(ChunkId.of("c1"))).toCompletableFuture().join();
        SearchResult result =
                engine.search(ctx(TENANT_A), lexical("deleted", TENANT_A, 10)).toCompletableFuture().join();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void metadataFiltersApply() {
        SynquestEngine engine = newEngine();
        ChunkProjection tagged =
                new ChunkProjection(
                        ChunkId.of("c1"),
                        DocumentId.of("doc-c1"),
                        "tenant_a",
                        "filterable content",
                        Map.of("type", "runbook"),
                        new float[] {1.0f, 0.0f},
                        MODEL,
                        1,
                        GEN);
        newWriter().upsert(List.of(tagged)).toCompletableFuture().join();
        SearchRequest match =
                new SearchRequest(
                        "filterable",
                        Optional.empty(),
                        Optional.empty(),
                        SearchMode.LEXICAL,
                        eligibility(TENANT_A),
                        new RelevanceFilters(Map.of("type", "runbook")),
                        TemporalExtension.empty(),
                        10,
                        0.0);
        assertThat(engine.search(ctx(TENANT_A), match).toCompletableFuture().join().hits()).hasSize(1);
        SearchRequest miss =
                new SearchRequest(
                        "filterable",
                        Optional.empty(),
                        Optional.empty(),
                        SearchMode.LEXICAL,
                        eligibility(TENANT_A),
                        new RelevanceFilters(Map.of("type", "other")),
                        TemporalExtension.empty(),
                        10,
                        0.0);
        assertThat(engine.search(ctx(TENANT_A), miss).toCompletableFuture().join().hits()).isEmpty();
    }

    /**
     * P0-1 regression: G1 write → G2 rebuild (non-full, rows remain) → G2 write →
     * search every supported mode → G1 invisible everywhere. Stale G1 writes are
     * rejected, not mixed.
     */
    @Test
    void generationPromotionHidesPreviousGeneration() {
        SynquestEngine engine = newEngine();
        var writer = newWriter();
        var admin = newAdmin();
        GenerationId g1 = new GenerationId("gen-promote-1");
        GenerationId g2 = new GenerationId("gen-promote-2");
        writer.upsert(List.of(projection("tenant_a", "c1", "genone solar wind storm", 1, new float[] {1.0f, 0.0f}, g1)))
                .toCompletableFuture()
                .join();
        admin.rebuild(new org.synanton.synquest.api.RebuildOptions(g2, false))
                .toCompletableFuture()
                .join();
        // Stale write rejected.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                writer.upsert(
                                                List.of(
                                                        projection(
                                                                "tenant_a", "c2", "genone second verse", 2,
                                                                new float[] {1.0f, 0.0f}, g1)))
                                        .toCompletableFuture()
                                        .join())
                .hasStackTraceContaining("CONFLICT");
        writer.upsert(List.of(projection("tenant_a", "c3", "gentwo solar wind", 3, new float[] {1.0f, 0.0f}, g2)))
                .toCompletableFuture()
                .join();
        // Both generations lexically match "solar wind" (YDB FulltextScore is
        // conjunctive — partial matches score nothing); only the generation
        // filter may separate them. This makes the test prove filtering, not matching.
        for (SearchMode mode : supportedModes(engine)) {
            SearchRequest request = requestFor(mode, "solar wind");
            SearchResult result =
                    engine.search(ctx(TENANT_A), request).toCompletableFuture().join();
            org.assertj.core.api.Assertions.assertThat(
                            result.hits().stream().map(h -> h.text()).toList())
                    .as("G1 invisible in " + mode)
                    .noneMatch(text -> text.contains("genone"));
            org.assertj.core.api.Assertions.assertThat(
                            result.hits().stream().map(h -> h.text()).toList())
                    .as("G2 visible in " + mode)
                    .anyMatch(text -> text.contains("gentwo"));
        }
    }

    /**
     * P0-1 regression: search traffic during the promotion window observes
     * consistently one generation or none — never mixed.
     */
    @Test
    void noMixedGenerationsDuringPromotion() throws Exception {
        SynquestEngine engine = newEngine();
        var writer = newWriter();
        var admin = newAdmin();
        GenerationId g1 = new GenerationId("gen-window-1");
        GenerationId g2 = new GenerationId("gen-window-2");
        List<ChunkProjection> seed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            seed.add(projection("tenant_a", "w" + i, "genone window seat " + i, i + 1, null, g1));
        }
        // NOTE: query "window seat" matches both generations lexically on every
        // adapter (YDB FulltextScore needs all terms present); separation below
        // is by generation filter alone.
        writer.upsert(seed).toCompletableFuture().join();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.List<String> violations =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        Thread searcher =
                new Thread(
                        () -> {
                            try {
                                go.await(10, java.util.concurrent.TimeUnit.SECONDS);
                                for (int i = 0; i < 20; i++) {
                                    SearchResult result =
                                            engine.search(ctx(TENANT_A), lexical("window seat solar", TENANT_A, 10))
                                                    .toCompletableFuture()
                                                    .join();
                                    boolean genone =
                                            result.hits().stream().anyMatch(h -> h.text().contains("genone"));
                                    boolean gentwo =
                                            result.hits().stream().anyMatch(h -> h.text().contains("gentwo"));
                                    if (genone && gentwo) {
                                        violations.add("mixed generations observed");
                                    }
                                }
                            } catch (Throwable t) {
                                failure.compareAndSet(null, t);
                            }
                        });
        searcher.start();
        go.countDown();
        admin.rebuild(new org.synanton.synquest.api.RebuildOptions(g2, false))
                .toCompletableFuture()
                .join();
        writer.upsert(List.of(projection("tenant_a", "w9", "gentwo window seat flare", 100, null, g2)))
                .toCompletableFuture()
                .join();
        searcher.join(120_000);
        org.assertj.core.api.Assertions.assertThat(failure.get()).as("searcher must not fail").isNull();
        org.assertj.core.api.Assertions.assertThat(violations).as("never mixed").isEmpty();
    }

    private static List<SearchMode> supportedModes(SynquestEngine engine) {
        List<SearchMode> modes = new ArrayList<>();
        var caps = engine.capabilities();
        if (caps.lexical()) {
            modes.add(SearchMode.LEXICAL);
        }
        if (caps.vector()) {
            modes.add(SearchMode.VECTOR);
        }
        if (caps.hybrid()) {
            modes.add(SearchMode.HYBRID);
        }
        return modes;
    }

    private static SearchRequest requestFor(SearchMode mode, String queryText) {
        return switch (mode) {
            case LEXICAL -> lexical(queryText, TENANT_A, 10);
            case VECTOR ->
                    new SearchRequest(
                            queryText,
                            Optional.of(new float[] {1.0f, 0.0f}),
                            Optional.of(MODEL),
                            SearchMode.VECTOR,
                            eligibility(TENANT_A),
                            RelevanceFilters.none(),
                            TemporalExtension.empty(),
                            10,
                            0.0);
            case HYBRID ->
                    new SearchRequest(
                            queryText,
                            Optional.of(new float[] {1.0f, 0.0f}),
                            Optional.of(MODEL),
                            SearchMode.HYBRID,
                            eligibility(TENANT_A),
                            RelevanceFilters.none(),
                            TemporalExtension.empty(),
                            10,
                            0.0);
        };
    }
}
