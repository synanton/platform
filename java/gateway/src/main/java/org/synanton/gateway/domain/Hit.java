package org.synanton.gateway.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Hit(
        String contentRefId,
        int chunkOrdinal,
        double score,
        double scoreDense,
        double scoreLexical,
        boolean graphPromoted,
        String snippet,
        String sourceUri
) {
    public Hit withScore(double newScore) {
        return new Hit(contentRefId, chunkOrdinal, newScore, scoreDense, scoreLexical, graphPromoted, snippet, sourceUri);
    }

    public Hit withGraphPromoted(boolean promoted) {
        return new Hit(contentRefId, chunkOrdinal, score, scoreDense, scoreLexical, promoted, snippet, sourceUri);
    }
}
