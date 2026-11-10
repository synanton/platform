package org.synanton.common.resolution;

/** A tenant-scoped canonical candidate; retrieval scores are ranking signals, not probabilities. */
public record ResolutionCandidate(
        String entityId,
        String canonicalLabel,
        String canonicalType,
        double retrievalScore,
        String matchedField,
        String retrievalStrategy,
        String searchGeneration) {

    /** Backward-compatible constructor: no search generation. */
    public ResolutionCandidate(String entityId, String canonicalLabel, String canonicalType,
                               double retrievalScore, String matchedField, String retrievalStrategy) {
        this(entityId, canonicalLabel, canonicalType, retrievalScore, matchedField,
                retrievalStrategy, null);
    }
}
