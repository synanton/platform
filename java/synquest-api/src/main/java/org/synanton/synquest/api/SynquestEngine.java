package org.synanton.synquest.api;

import java.util.concurrent.CompletionStage;
import org.synanton.storage.contract.SecurityContext;

/**
 * Retrieval port used by Search 1.31 (proposal §10.1). Purely query-facing;
 * projection mutation lives in {@link SynquestIndexWriter}. Reads derived search
 * projections — never authoritative (invariant 11).
 */
public interface SynquestEngine {

    CompletionStage<SearchResult> search(SecurityContext context, SearchRequest request);

    SearchCapabilities capabilities();
}
