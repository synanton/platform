package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QueryUsage(
        @JsonProperty("wall_ms") long wallMs,
        @JsonProperty("query_embed_ms") long queryEmbedMs,
        @JsonProperty("query_input_chars") long queryInputChars,
        @JsonProperty("query_input_tokens") int queryInputTokens,
        @JsonProperty("embed_skipped") boolean embedSkipped
) {}
