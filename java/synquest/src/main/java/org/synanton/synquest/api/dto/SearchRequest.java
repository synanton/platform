package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SearchRequest(
        String tenant,
        String query,
        @JsonProperty("top_k") Integer topK,
        @JsonProperty("top_k_dense") Integer topKDense,
        @JsonProperty("top_k_lexical") Integer topKLexical,
        @JsonProperty("rrf_k") Integer rrfK
) {}
