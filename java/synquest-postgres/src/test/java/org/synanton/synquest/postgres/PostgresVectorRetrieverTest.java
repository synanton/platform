package org.synanton.synquest.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.BeforeEach;
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
import org.synanton.synquest.api.SearchCapabilities;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SearchResult;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.TemporalExtension;
import org.synanton.synquest.api.VectorRetriever;
import org.synanton.synquest.api.VectorSearchRequest;
import org.synanton.synquest.api.VectorSearchResult;

/**
 * SYN-VECTOR-001 B1.3: wrapper-vs-engine equivalence on live Postgres state.
 * No behavior change — the wrapper delegates through {@code engine.search(mode=VECTOR)}.
 */
class PostgresVectorRetrieverTest extends QuestPostgresFixture {

    private static final class RecordingEngine implements SynquestEngine {
        private final SynquestEngine delegate;
        private SearchRequest captured;

        RecordingEngine(SynquestEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletionStage<SearchResult> search(SecurityContext context, SearchRequest request) {
            captured = request;
            return delegate.search(context, request);
        }

        @Override
        public SearchCapabilities capabilities() {
            return delegate.capabilities();
        }
    }

    @BeforeEach
    void truncateState() {
        try (var connection = appConnection();
                var stmt = connection.createStatement()) {
            stmt.execute("TRUNCATE chunks, quest_generations");
        } catch (Exception e) {
            throw new IllegalStateException("truncate failed", e);
        }
    }

    @Test
    void shouldReturnSameHitsAsEngineVectorMode() {
        PostgresSynquestEngine engine = newEngine();
        String tenant = "t-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        GenerationId generation = new GenerationId("g1");
        EmbeddingModelRef model = new EmbeddingModelRef("test", "v1", "d");
        seed(engine, tenant, model, generation);

        RecordingEngine recording = new RecordingEngine(engine);
        PostgresVectorRetriever retriever = new PostgresVectorRetriever(recording);
        SecurityContext context = testContext(tenant);
        EligibilityConstraints eligibility = testEligibility(tenant);
        float[] query = new float[] {1.0f, 0.0f};

        VectorSearchResult vectorResult =
                retriever
                        .search(
                                context,
                                new VectorSearchRequest(query, Optional.of(model), eligibility, 10))
                        .toCompletableFuture()
                        .join();
        SearchRequest expected = directRequest(query, model, eligibility, 10);
        SearchResult directResult =
                engine.search(context, expected).toCompletableFuture().join();

        assertThat(recording.captured).isEqualTo(expected);
        assertThat(vectorResult.hits()).isEqualTo(directResult.hits());
        assertThat(vectorResult.totalEligible()).isEqualTo(directResult.totalEligible());
    }

    @Test
    void shouldReportVectorOnlyCapabilities() {
        PostgresSynquestEngine engine = newEngine();

        assertThat(new PostgresVectorRetriever(engine).capabilities())
                .isEqualTo(new SearchCapabilities(false, true, false, false, false, false, false, false));
    }

    private static SearchRequest directRequest(
            float[] query, EmbeddingModelRef model, EligibilityConstraints eligibility, int topK) {
        return new SearchRequest(
                "",
                Optional.of(query),
                Optional.of(model),
                SearchMode.VECTOR,
                eligibility,
                RelevanceFilters.none(),
                TemporalExtension.empty(),
                topK,
                VectorRetriever.MIN_SCORE_NO_THRESHOLD);
    }

    private static void seed(
            PostgresSynquestEngine engine,
            String tenant,
            EmbeddingModelRef model,
            GenerationId generation) {
        engine.upsert(
                        List.of(
                                chunk(tenant, "c1", "alpha text", new float[] {1.0f, 0.0f}, 1L, model, generation),
                                chunk(tenant, "c2", "beta text", new float[] {0.0f, 1.0f}, 2L, model, generation)))
                .toCompletableFuture()
                .join();
    }

    private static ChunkProjection chunk(
            String tenant,
            String id,
            String text,
            float[] embedding,
            long orderingKey,
            EmbeddingModelRef model,
            GenerationId generation) {
        return new ChunkProjection(
                ChunkId.of(id),
                new DocumentId("d-" + id),
                tenant,
                text,
                Map.of(),
                embedding,
                model,
                orderingKey,
                generation);
    }

    private static SecurityContext testContext(String tenant) {
        return SecurityContext.user(
                new TenantScope(tenant), new PrincipalRef("user", "u1"), new PolicyContext("p", "1"));
    }

    private static EligibilityConstraints testEligibility(String tenant) {
        return EligibilityConstraints.from(
                new TenantScope(tenant), List.of(new PrincipalRef("user", "u1")), new PolicyContext("p", "1"));
    }
}
