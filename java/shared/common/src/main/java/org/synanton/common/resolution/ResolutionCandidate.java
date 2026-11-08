package org.synanton.common.resolution;

/** A tenant-scoped canonical candidate; retrieval scores are ranking signals, not probabilities. */
public record ResolutionCandidate(
        String entityId,
        String canonicalLabel,
        String canonicalType,
        double retrievalScore,
        String matchedField,
        String retrievalStrategy) {}
