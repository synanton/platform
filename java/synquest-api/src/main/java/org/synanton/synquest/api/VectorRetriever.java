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

    CompletionStage<VectorSearchResult> search(SecurityContext context, VectorSearchRequest request);

    SearchCapabilities capabilities();
}
