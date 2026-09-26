package org.synanton.synvault.api;

import java.util.Map;
import java.util.Objects;

/**
 * Chunk listing filter. Ranking-time relevance concern only — never eligibility
 * (eligibility lives in {@code EligibilityConstraints} on the search port).
 */
public record ChunkQuery(Map<String, String> mustMatchMetadata) {
    public ChunkQuery {
        mustMatchMetadata = mustMatchMetadata == null ? Map.of() : Map.copyOf(mustMatchMetadata);
    }

    public static ChunkQuery all() {
        return new ChunkQuery(Map.of());
    }
}
