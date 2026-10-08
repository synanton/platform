package org.synanton.synquest.postgres;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.synquest.api.RelevanceFilters;
import org.synanton.synquest.api.SearchCapabilities;
import org.synanton.synquest.api.SearchMode;
import org.synanton.synquest.api.SearchRequest;
import org.synanton.synquest.api.SynquestEngine;
import org.synanton.synquest.api.TemporalExtension;
import org.synanton.synquest.api.VectorRetriever;
import org.synanton.synquest.api.VectorSearchRequest;
import org.synanton.synquest.api.VectorSearchResult;

/**
 * {@link VectorRetriever} over {@link PostgresSynquestEngine} (SYN-VECTOR-001 B1.3).
 * Delegates through {@code engine.search(mode=VECTOR)} — the exact path a vector-mode
 * search takes — so equivalence with the engine is structural, not asserted.
 * {@code queryText} is empty by design; the record invariant is satisfied by the
 * always-present embedding (Reading 1: the retriever never embeds).
 */
public final class PostgresVectorRetriever implements VectorRetriever {

    private final SynquestEngine engine;

    public PostgresVectorRetriever(SynquestEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public CompletionStage<VectorSearchResult> search(SecurityContext context, VectorSearchRequest request) {
        SearchRequest full =
                new SearchRequest(
                        "",
                        Optional.of(request.queryEmbedding()),
                        request.embeddingModelRef(),
                        SearchMode.VECTOR,
                        request.eligibility(),
                        RelevanceFilters.none(),
                        TemporalExtension.empty(),
                        request.topK(),
                        VectorRetriever.MIN_SCORE_NO_THRESHOLD);
        return engine
                .search(context, full)
                .thenApply(result -> new VectorSearchResult(result.hits(), result.totalEligible()));
    }

    @Override
    public SearchCapabilities capabilities() {
        return new SearchCapabilities(false, true, false, false, false, false, false, false);
    }
}
