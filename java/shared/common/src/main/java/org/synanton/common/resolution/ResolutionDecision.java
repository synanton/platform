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
        String retrievalStrategy) {
    public ResolutionDecision {
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
