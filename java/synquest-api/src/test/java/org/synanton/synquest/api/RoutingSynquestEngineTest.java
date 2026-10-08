package org.synanton.synquest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.TenantScope;

/**
 * SYN-VECTOR-001 B1.4: routing contract of {@link RoutingSynquestEngine}.
 * Hand-rolled fakes (B1.3 precedent — no new test dependencies); recording lists
 * stand in for verify(..., never()).
 */
class RoutingSynquestEngineTest {

    private static final EmbeddingModelRef MODEL = new EmbeddingModelRef("bge-base", "v1", "abc123");

    private static final class FakeEngine implements SynquestEngine {
        private final List<SearchRequest> calls = new ArrayList<>();
        private final SearchResult result;
        private final SearchCapabilities capabilities;

        FakeEngine(SearchResult result, SearchCapabilities capabilities) {
            this.result = result;
            this.capabilities = capabilities;
        }

        @Override
        public CompletionStage<SearchResult> search(SecurityContext context, SearchRequest request) {
            calls.add(request);
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public SearchCapabilities capabilities() {
            return capabilities;
        }
    }

    private static final class FakeRetriever implements VectorRetriever {
        private final List<VectorSearchRequest> calls = new ArrayList<>();
        private final VectorSearchResult result;

        FakeRetriever(VectorSearchResult result) {
            this.result = result;
        }

        @Override
        public CompletionStage<VectorSearchResult> search(SecurityContext context, VectorSearchRequest request) {
            calls.add(request);
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public SearchCapabilities capabilities() {
            return new SearchCapabilities(false, true, false, false, false, false, false, false);
        }
    }

    private static SecurityContext testContext() {
        return SecurityContext.user(
                new TenantScope("demo"), new PrincipalRef("user", "u1"), new PolicyContext("p", "1"));
    }

    private static EligibilityConstraints testEligibility() {
        return EligibilityConstraints.from(
                new TenantScope("demo"),
                List.of(new PrincipalRef("user", "u1")),
                new PolicyContext("p", "1"));
    }

    private static SearchRequest engineRequest(
            SearchMode mode,
            Optional<float[]> embedding,
            String queryText,
            Map<String, String> filters,
            double minScore) {
        return new SearchRequest(
                queryText,
                embedding,
                Optional.of(MODEL),
                mode,
                testEligibility(),
                new RelevanceFilters(filters),
                TemporalExtension.empty(),
                10,
                minScore);
    }

    private static SearchHit testHit() {
        return new SearchHit(ChunkId.of("c1"), new DocumentId("d1"), 0.9, "text", Map.of());
    }

    @Test
    void shouldFallThroughNonVectorRequests() {
        SearchResult engineResult = new SearchResult(List.of(testHit()), 1, Map.of(ChunkId.of("c1"), "snippet"));
        FakeEngine engine = new FakeEngine(engineResult, SearchCapabilities.pocBaseline());
        FakeRetriever retriever =
                new FakeRetriever(new VectorSearchResult(List.of(), 0));
        RoutingSynquestEngine facade = new RoutingSynquestEngine(engine, retriever);
        SearchRequest request =
                engineRequest(SearchMode.LEXICAL, Optional.empty(), "text", Map.of(), 0.0);

        SearchResult result =
                facade.search(testContext(), request).toCompletableFuture().join();

        assertThat(engine.calls).containsExactly(request);
        assertThat(retriever.calls).isEmpty();
        assertThat(result).isEqualTo(engineResult);
    }

    @Test
    void shouldFallThroughVectorRequestsWithoutEmbedding() {
        SearchResult engineResult = new SearchResult(List.of(), 0, Map.of());
        FakeEngine engine = new FakeEngine(engineResult, SearchCapabilities.pocBaseline());
        FakeRetriever retriever =
                new FakeRetriever(new VectorSearchResult(List.of(), 0));
        RoutingSynquestEngine facade = new RoutingSynquestEngine(engine, retriever);
        SearchRequest request =
                engineRequest(SearchMode.VECTOR, Optional.empty(), "text", Map.of(), 0.0);

        SearchResult result =
                facade.search(testContext(), request).toCompletableFuture().join();

        assertThat(engine.calls).containsExactly(request);
        assertThat(retriever.calls).isEmpty();
        assertThat(result).isEqualTo(engineResult);
    }

    @Test
    void shouldRouteNarrowVectorRequestsToRetriever() {
        float[] query = new float[] {0.1f, 0.2f};
        FakeEngine engine =
                new FakeEngine(
                        new SearchResult(List.of(), 0, Map.of()), SearchCapabilities.pocBaseline());
        VectorSearchResult retrieverResult =
                new VectorSearchResult(List.of(testHit()), 1);
        FakeRetriever retriever = new FakeRetriever(retrieverResult);
        RoutingSynquestEngine facade = new RoutingSynquestEngine(engine, retriever);
        SearchRequest request =
                engineRequest(
                        SearchMode.VECTOR,
                        Optional.of(query),
                        "",
                        Map.of(),
                        VectorRetriever.MIN_SCORE_NO_THRESHOLD);

        SearchResult result =
                facade.search(testContext(), request).toCompletableFuture().join();

        assertThat(retriever.calls)
                .containsExactly(
                        new VectorSearchRequest(query, Optional.of(MODEL), testEligibility(), 10));
        assertThat(engine.calls).isEmpty();
        assertThat(result.hits()).isEqualTo(retrieverResult.hits());
        assertThat(result.totalEligible()).isEqualTo(retrieverResult.totalEligible());
        assertThat(result.highlights()).isEmpty();
    }

    private static Stream<String> wideRequests() {
        return Stream.of("queryText", "filters", "temporal", "minScore");
    }

    @ParameterizedTest(name = "shouldThrowOnWideField_{0}")
    @MethodSource("wideRequests")
    void shouldThrowOnWideVectorRequests(String field) {
        FakeEngine engine =
                new FakeEngine(
                        new SearchResult(List.of(), 0, Map.of()), SearchCapabilities.pocBaseline());
        FakeRetriever retriever =
                new FakeRetriever(new VectorSearchResult(List.of(), 0));
        RoutingSynquestEngine facade = new RoutingSynquestEngine(engine, retriever);
        SearchRequest request = wideRequestFor(field);

        assertThatThrownBy(() -> facade.search(testContext(), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(field);
        assertThat(engine.calls).isEmpty();
        assertThat(retriever.calls).isEmpty();
    }

    private static SearchRequest wideRequestFor(String field) {
        float[] query = new float[] {0.1f, 0.2f};
        String queryText = field.equals("queryText") ? "text" : "";
        Map<String, String> filters = field.equals("filters") ? Map.of("k", "v") : Map.of();
        double minScore =
                field.equals("minScore") ? 0.5 : VectorRetriever.MIN_SCORE_NO_THRESHOLD;
        return new SearchRequest(
                queryText,
                Optional.of(query),
                Optional.of(MODEL),
                SearchMode.VECTOR,
                testEligibility(),
                new RelevanceFilters(filters),
                field.equals("temporal") ? temporal() : TemporalExtension.empty(),
                10,
                minScore);
    }

    private static TemporalExtension temporal() {
        return new TemporalExtension(
                Optional.of(java.time.Instant.parse("2026-01-01T00:00:00Z")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);
    }

    @Test
    void shouldReturnEngineCapabilities() {
        SearchCapabilities capabilities =
                new SearchCapabilities(true, true, true, true, true, false, false, false);
        FakeEngine engine =
                new FakeEngine(new SearchResult(List.of(), 0, Map.of()), capabilities);
        FakeRetriever retriever =
                new FakeRetriever(new VectorSearchResult(List.of(), 0));
        RoutingSynquestEngine facade = new RoutingSynquestEngine(engine, retriever);

        assertThat(facade.capabilities()).isEqualTo(capabilities);
    }
}
