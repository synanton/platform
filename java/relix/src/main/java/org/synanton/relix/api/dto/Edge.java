package org.synanton.relix.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record Edge(
        @JsonProperty("edge_id") UUID edgeId,
        @JsonProperty("from_entity_id") UUID fromEntityId,
        @JsonProperty("to_entity_id") UUID toEntityId,
        String verb,
        double confidence,
        @JsonProperty("source_refs") List<SourceRef> sourceRefs
) {}
