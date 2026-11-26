package org.synanton.synflux.resolution;

/** Stable provenance for one mention-to-canonical resolution outcome. */
public record ResolutionResult(
        String mentionId,
        String normalizedSurface,
        String proposedType,
        ResolutionStatus status,
        String canonicalId,
        String canonicalLabel,
        String matchMethod,
        String reason,
        String strategyVersion,
        String catalogVersion,
        String catalogDigest) {
}
