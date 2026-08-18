package org.synanton.relix.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record Entity(
        @JsonProperty("entity_id") UUID entityId,
        String label,
        String type,
        double confidence,
        @JsonProperty("source_refs") List<SourceRef> sourceRefs
) {}
