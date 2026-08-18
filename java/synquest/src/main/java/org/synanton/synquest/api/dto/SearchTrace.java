package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SearchTrace(
        @JsonProperty("query_embed_ms") long queryEmbedMs,
        @JsonProperty("dense_search_ms") long denseSearchMs,
        @JsonProperty("lexical_search_ms") long lexicalSearchMs,
        @JsonProperty("fusion_ms") long fusionMs,
        @JsonProperty("total_ms") long totalMs,
        @JsonProperty("index_generation") long indexGeneration
) {}
