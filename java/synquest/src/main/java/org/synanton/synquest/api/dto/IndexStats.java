package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record IndexStats(
        String tenant,
        @JsonProperty("doc_count") int docCount,
        @JsonProperty("index_generation") long indexGeneration,
        String status
) {}
