package org.synanton.synquest.search;

/** Engine-neutral query knobs (#550); no engine types cross this boundary. */
public record SearchQuery(
        String text,
        Integer topK,
        Integer topKDense,
        Integer topKLexical,
        Integer rrfK,
        Boolean rerank,
        Integer rerankCandidates,
        String expand,
        Integer expandMaxChunks,
        String executionMode) {}
