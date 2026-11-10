package org.synanton.common.resolution;

import java.util.Map;

/** Durable mention resolution decision, including diagnostics and policy/ontology versions. */
public record ResolutionDecision(
        String tenantId,
        String sourceVersionId,
        String mentionId,
        ResolutionOutcome outcome,
        String reasonCode,
        String resolvedEntityId,
        String canonicalLabel,
        double bestScore,
        double runnerUpScore,
        String strategyVersion,
        String ontologyVersion,
        Map<String, Object> diagnostics,
        String retrievalStrategy,
        long fencingToken,
        String searchGeneration) {
    public ResolutionDecision {
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    /** Backward-compatible constructor: no fencing token or search generation. */
    public ResolutionDecision(String tenantId, String sourceVersionId, String mentionId,
                              ResolutionOutcome outcome, String reasonCode, String resolvedEntityId,
                              String canonicalLabel, double bestScore, double runnerUpScore,
                              String strategyVersion, String ontologyVersion,
                              Map<String, Object> diagnostics, String retrievalStrategy) {
        this(tenantId, sourceVersionId, mentionId, outcome, reasonCode, resolvedEntityId,
                canonicalLabel, bestScore, runnerUpScore, strategyVersion, ontologyVersion,
                diagnostics, retrievalStrategy, 0L, null);
    }

    /** Backward-compatible constructor: fencing token, no search generation. */
    public ResolutionDecision(String tenantId, String sourceVersionId, String mentionId,
                              ResolutionOutcome outcome, String reasonCode, String resolvedEntityId,
                              String canonicalLabel, double bestScore, double runnerUpScore,
                              String strategyVersion, String ontologyVersion,
                              Map<String, Object> diagnostics, String retrievalStrategy,
                              long fencingToken) {
        this(tenantId, sourceVersionId, mentionId, outcome, reasonCode, resolvedEntityId,
                canonicalLabel, bestScore, runnerUpScore, strategyVersion, ontologyVersion,
                diagnostics, retrievalStrategy, fencingToken, null);
    }

    /** Claim attempts are monotonic fences: later attempts always win. */
    public ResolutionDecision withFencingToken(long token) {
        return new ResolutionDecision(tenantId, sourceVersionId, mentionId, outcome, reasonCode,
                resolvedEntityId, canonicalLabel, bestScore, runnerUpScore, strategyVersion,
                ontologyVersion, diagnostics, retrievalStrategy, token, searchGeneration);
    }
}
