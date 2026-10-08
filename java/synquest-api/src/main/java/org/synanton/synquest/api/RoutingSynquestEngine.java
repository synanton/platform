package org.synanton.synquest.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.synanton.storage.contract.SecurityContext;

/**
 * Routing facade over a {@link SynquestEngine} and its {@link VectorRetriever}
 * (SYN-VECTOR-001 B1.4). First behavioral class in {@code synquest-api}: a stable
 * port-owned type that composes ports rather than declaring them.
 *
 * <p>Routing contract:
 *
 * <ul>
 *   <li>Non-VECTOR requests (LEXICAL, HYBRID, …) fall through to the engine.
 *   <li>VECTOR requests without an embedding fall through to the engine (today's
 *       engines return empty results there — preserved by construction).
 *   <li>VECTOR requests with an embedding and all-narrow fields route to the retriever.
 *   <li>VECTOR requests with an embedding and any wide field set throw
 *       {@link IllegalArgumentException} naming the fields. Silent narrowing is
 *       rejected: the narrow retriever contract cannot represent text, filters,
 *       temporal constraints, or score floors.
 * </ul>
 *
 * <p>Reading-1 claim: zero change for direct-engine callers. Facade callers either get
 * the narrow retriever contract or an explicit failure — no silent narrowing. Routed
 * results carry empty highlights, matching the retriever's {@link VectorSearchResult}
 * shape (engines may populate degenerate snippets for textless vector queries; the
 * facade does not reproduce them).
 *
 * <p>Widening escape hatch: if {@link VectorSearchRequest} grows filters, temporal, or
 * score-floor support, the corresponding clause comes off both predicates below.
 */
public final class RoutingSynquestEngine implements SynquestEngine {

    private final SynquestEngine engine;
    private final VectorRetriever retriever;

    public RoutingSynquestEngine(SynquestEngine engine, VectorRetriever retriever) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.retriever = Objects.requireNonNull(retriever, "retriever");
    }

    /**
     * Routes narrow vector requests through the retriever; everything else falls
     * through to the engine, except wide vector requests which fail loud.
     *
     * @throws IllegalArgumentException when the request is mode=VECTOR with an
     *     embedding but carries wide fields (queryText, filters, temporal, minScore)
     *     the narrow retriever contract cannot represent
     */
    @Override
    public CompletionStage<SearchResult> search(SecurityContext context, SearchRequest request) {
        if (isLossyVectorRequest(request)) {
            throw new IllegalArgumentException(
                    "facade routes VECTOR through the narrow retriever; use the engine directly for: "
                            + describeWideFields(request));
        }
        if (isNarrowVectorRequest(request)) {
            return routeToRetriever(context, request);
        }
        return engine.search(context, request);
    }

    @Override
    public SearchCapabilities capabilities() {
        return engine.capabilities();
    }

    private CompletionStage<SearchResult> routeToRetriever(SecurityContext context, SearchRequest request) {
        VectorSearchRequest vectorRequest =
                new VectorSearchRequest(
                        request.queryEmbedding().orElseThrow(
                                () -> new IllegalStateException("queryEmbedding present per isNarrowVectorRequest")),
                        request.embeddingModelRef(),
                        request.eligibility(),
                        request.topK());
        return retriever
                .search(context, vectorRequest)
                .thenApply(vsr -> new SearchResult(vsr.hits(), vsr.totalEligible(), Map.of()));
    }

    private static boolean isNarrowVectorRequest(SearchRequest request) {
        return request.mode() == SearchMode.VECTOR
                && request.queryEmbedding().isPresent()
                && wideFields(request).isEmpty();
    }

    private static boolean isLossyVectorRequest(SearchRequest request) {
        return request.mode() == SearchMode.VECTOR
                && request.queryEmbedding().isPresent()
                && !wideFields(request).isEmpty();
    }

    private static List<String> wideFields(SearchRequest request) {
        List<String> fields = new ArrayList<>();
        if (!request.queryText().isEmpty()) {
            fields.add("queryText");
        }
        if (!request.filters().mustMatchMetadata().isEmpty()) {
            fields.add("filters");
        }
        if (!request.temporal().isEmpty()) {
            fields.add("temporal");
        }
        if (request.minScore() != VectorRetriever.MIN_SCORE_NO_THRESHOLD) {
            fields.add("minScore");
        }
        return fields;
    }

    private static String describeWideFields(SearchRequest request) {
        return String.join(", ", wideFields(request));
    }
}
