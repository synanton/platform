package org.synanton.storage.testkit;

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
        return new ChunkProjection(
                ChunkId.of(chunk),
                DocumentId.of("doc-" + chunk),
                tenant,
                text,
                Map.of("type", "note"),
                new float[] {1.0f, 0.0f},
                MODEL,
                orderingKey,
                GEN);
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
    void eligibilityIsPreRanking() {
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
}
