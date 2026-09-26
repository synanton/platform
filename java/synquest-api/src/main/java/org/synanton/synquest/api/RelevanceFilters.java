package org.synanton.synquest.api;

import java.util.Map;

/**
 * Ranking-time relevance filters (metadata). Optional, and strictly separated from
 * eligibility: filters influence ranking/selection, never candidate eligibility.
 */
public record RelevanceFilters(Map<String, String> mustMatchMetadata) {
    public RelevanceFilters {
        mustMatchMetadata = mustMatchMetadata == null ? Map.of() : Map.copyOf(mustMatchMetadata);
    }

    public static RelevanceFilters none() {
        return new RelevanceFilters(Map.of());
    }
}
