package org.synanton.relix.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GraphTrace(
        String shape,
        @JsonProperty("graph_generation") long graphGeneration,
        @JsonProperty("candidate_count") int candidateCount,
        @JsonProperty("traversal_ms") long traversalMs,
        @JsonProperty("total_ms") long totalMs
) {}
