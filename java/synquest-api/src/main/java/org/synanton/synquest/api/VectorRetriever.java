package org.synanton.synquest.api;

import java.util.concurrent.CompletionStage;
import org.synanton.storage.contract.SecurityContext;

/**
 * Vector-only retrieval port (SYN-VECTOR-001 B1). Lower-level SPI behind the
 * {@link SynquestEngine} facade (which keeps the public shape per B1.4);
 * no shared supertype needed. Transport-agnostic: in-process and external-service
 * (HTTP/gRPC/socket) implementations honour the same contract — adapter-side
 * clients live outside {@code synquest-api} per the module boundary guard.
 * {@code capabilities()} reuses {@link SearchCapabilities} with lexical-only
 * flags false by contract.
 */
public interface VectorRetriever {

    /**
     * Score floor meaning "no threshold": callers own thresholding (retrieval is
     * topK-bounded, filtering happens caller-side). Relies on Java-side score
     * comparison in every adapter — verify if any adapter ever moves score
     * filtering into the query itself, where this value may not round-trip.
     */
    double MIN_SCORE_NO_THRESHOLD = Double.NEGATIVE_INFINITY;

    CompletionStage<VectorSearchResult> search(SecurityContext context, VectorSearchRequest request);

    SearchCapabilities capabilities();
}
