package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SearchRequest(
        String tenant,
        String query,
        @JsonProperty("top_k") Integer topK,
        @JsonProperty("top_k_dense") Integer topKDense,
        @JsonProperty("top_k_lexical") Integer topKLexical,
        @JsonProperty("rrf_k") Integer rrfK,
        /** Rerank the fused candidates with the cross-encoder (requires synquest.rerank.enabled). */
        @JsonProperty("rerank") Boolean rerank,
        /** How many fused candidates to rerank before cutting to top_k (default synquest.rerank.default-candidates). */
        @JsonProperty("rerank_candidates") Integer rerankCandidates,
        /** "section": small-to-big. Each hit pulls in its whole document section, in reading order (B2/T05). */
        @JsonProperty("expand") String expand,
        /** Max chunks per expanded section (default 8); a larger section is windowed around the hit. */
        @JsonProperty("expand_max_chunks") Integer expandMaxChunks
) {
    public SearchRequest(String tenant, String query, Integer topK, Integer topKDense, Integer topKLexical, Integer rrfK) {
        this(tenant, query, topK, topKDense, topKLexical, rrfK, null, null, null, null);
    }

    public SearchRequest(String tenant, String query, Integer topK, Integer topKDense, Integer topKLexical, Integer rrfK,
                         Boolean rerank, Integer rerankCandidates) {
        this(tenant, query, topK, topKDense, topKLexical, rrfK, rerank, rerankCandidates, null, null);
    }
}
