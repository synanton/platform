package org.synanton.synquest.api;

import java.util.List;
import java.util.Objects;

/**
 * Vector-only retrieval result (SYN-VECTOR-001 B1). Same shape as
 * {@link SearchResult} minus highlights — highlights are a lexical feature and a
 * vector-only result must not populate them. Reused by shape, not by subtyping
 * (records are final). The B1.4 facade translates back to {@link SearchResult}
 * with an empty highlights map.
 *
 * @param hits          ranked hits (already eligibility-filtered)
 * @param totalEligible total eligible candidates before topK truncation
 */
public record VectorSearchResult(List<SearchHit> hits, int totalEligible) {
    public VectorSearchResult {
        Objects.requireNonNull(hits, "hits");
        hits = List.copyOf(hits);
        if (totalEligible < 0) {
            throw new IllegalArgumentException("totalEligible must be >= 0");
        }
    }
}
