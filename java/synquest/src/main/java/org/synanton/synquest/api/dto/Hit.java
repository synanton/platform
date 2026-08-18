package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record Hit(
        @JsonProperty("content_ref_id") UUID contentRefId,
        @JsonProperty("chunk_ordinal") int chunkOrdinal,
        double score,
        @JsonProperty("score_dense") double scoreDense,
        @JsonProperty("score_lexical") double scoreLexical,
        @JsonProperty("rank_dense") int rankDense,
        @JsonProperty("rank_lexical") int rankLexical,
        String snippet,
        @JsonProperty("source_uri") String sourceUri
) {}
