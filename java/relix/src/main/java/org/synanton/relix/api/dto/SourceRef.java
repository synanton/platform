package org.synanton.relix.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record SourceRef(
        @JsonProperty("content_ref_id") UUID contentRefId,
        @JsonProperty("chunk_ordinals") List<Integer> chunkOrdinals
) {}
