package org.synanton.synquest.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.synanton.storage.contract.ChunkId;

/**
 * Retrieval result. Highlights obey the same eligibility rule as primary results —
 * an adapter must not leak snippet content for ineligible candidates (§8.4).
 *
 * @param hits           ranked hits (already eligibility-filtered)
 * @param totalEligible  total eligible candidates before topK truncation
 * @param highlights     eligibility-safe snippets keyed by chunk
 */
public record SearchResult(List<SearchHit> hits, int totalEligible, Map<ChunkId, String> highlights) {
    public SearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        highlights = highlights == null ? Map.of() : Map.copyOf(highlights);
        if (totalEligible < 0) {
            throw new IllegalArgumentException("totalEligible must be >= 0");
        }
    }
}
