package org.synanton.relix.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GraphStats(
        String tenant,
        @JsonProperty("entity_count") int entityCount,
        @JsonProperty("edge_count") int edgeCount,
        @JsonProperty("load_time_ms") long loadTimeMs,
        @JsonProperty("graph_generation") long graphGeneration
) {}
