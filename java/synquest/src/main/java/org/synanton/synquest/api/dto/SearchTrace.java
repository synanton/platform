package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SearchTrace(
        @JsonProperty("query_embed_ms") long queryEmbedMs,
        @JsonProperty("dense_search_ms") long denseSearchMs,
        @JsonProperty("lexical_search_ms") long lexicalSearchMs,
        @JsonProperty("fusion_ms") long fusionMs,
        @JsonProperty("total_ms") long totalMs,
        @JsonProperty("index_generation") long indexGeneration,
        @JsonProperty("rerank_ms") @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Long rerankMs,
        @JsonProperty("rerank_candidates") @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Integer rerankCandidates
) {
    public SearchTrace(long queryEmbedMs, long denseSearchMs, long lexicalSearchMs, long fusionMs, long totalMs,
                       long indexGeneration) {
        this(queryEmbedMs, denseSearchMs, lexicalSearchMs, fusionMs, totalMs, indexGeneration, null, null);
    }
}
