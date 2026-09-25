package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
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
        @JsonProperty("source_uri") String sourceUri,
        @JsonProperty("page_start") int pageStart,
        @JsonProperty("page_end") int pageEnd,
        @JsonProperty("section_path") String sectionPath,
        String heading,
        @JsonProperty("source_elements") List<String> sourceElements,
        @JsonProperty("token_count") int tokenCount,
        @JsonProperty("structured_content") String structuredContent,
        @JsonProperty("is_partial_section") boolean isPartialSection,
        @JsonProperty("ingest_usage") String ingestUsage,
        /** Cross-encoder relevance when the search was reranked; hits are then ordered by it. */
        @JsonProperty("score_rerank") @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        Double scoreRerank,
        /** Document section of the chunk (B2/T05 hierarchy), when the index has one. */
        @JsonProperty("section_id") @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String sectionId,
        /** Set on chunks added by expand=section: the chunk ID ("ref#ordinal") of the hit that pulled them in. */
        @JsonProperty("expanded_from") @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String expandedFrom
) {
    public Hit(UUID contentRefId, int chunkOrdinal, double score, double scoreDense, double scoreLexical, int rankDense,
               int rankLexical, String snippet, String sourceUri, int pageStart, int pageEnd, String sectionPath,
               String heading, List<String> sourceElements, int tokenCount, String structuredContent,
               boolean isPartialSection, String ingestUsage) {
        this(contentRefId, chunkOrdinal, score, scoreDense, scoreLexical, rankDense, rankLexical, snippet, sourceUri,
                pageStart, pageEnd, sectionPath, heading, sourceElements, tokenCount, structuredContent,
                isPartialSection, ingestUsage, null, null, null);
    }

    public Hit withScoreRerank(double s) {
        return new Hit(contentRefId, chunkOrdinal, score, scoreDense, scoreLexical, rankDense, rankLexical, snippet,
                sourceUri, pageStart, pageEnd, sectionPath, heading, sourceElements, tokenCount, structuredContent,
                isPartialSection, ingestUsage, s, sectionId, expandedFrom);
    }

    public Hit withExpansion(double inheritedScore, Double inheritedRerank, String from) {
        return new Hit(contentRefId, chunkOrdinal, inheritedScore, scoreDense, scoreLexical, rankDense, rankLexical,
                snippet, sourceUri, pageStart, pageEnd, sectionPath, heading, sourceElements, tokenCount,
                structuredContent, isPartialSection, ingestUsage, inheritedRerank, sectionId, from);
    }

    public String chunkId() {
        return contentRefId + "#" + chunkOrdinal;
    }
}
