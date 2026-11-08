package org.synanton.common.resolution;

/** A tenant-scoped canonical candidate; search scores are ranking signals, not probabilities. */
public record ResolutionCandidate(
        String entityId,
        String canonicalLabel,
        String canonicalType,
        double score,
        String retrievalSource) {}
