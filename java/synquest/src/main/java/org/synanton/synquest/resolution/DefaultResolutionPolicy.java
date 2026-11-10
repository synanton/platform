package org.synanton.synquest.resolution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.synanton.common.resolution.ResolutionCandidate;
import org.synanton.common.resolution.ResolutionContext;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.common.resolution.ResolutionPolicy;
import org.synanton.common.resolution.TypeCompatibility;

/**
 * Deterministic, pure policy (PR #4b.3). It never writes graph state.
 *
 * <p>Scores are retrieval scores (RRF today), not probabilities. Automatic
 * resolution requires an absolute floor AND a margin over the runner-up AND
 * ontology type compatibility. Unknown type is REVIEW_REQUIRED; incompatible
 * type is UNRESOLVED. Equal scores are broken deterministically by entity id.
 */
public final class DefaultResolutionPolicy implements ResolutionPolicy {

    public static final String NO_CANDIDATES = "NO_CANDIDATES";
    public static final String UNKNOWN_PROPOSED_TYPE = "UNKNOWN_PROPOSED_TYPE";
    public static final String TYPE_INCOMPATIBLE = "TYPE_INCOMPATIBLE";
    public static final String BELOW_ABSOLUTE_THRESHOLD = "BELOW_ABSOLUTE_THRESHOLD";
    public static final String LOW_MARGIN_AMBIGUOUS = "LOW_MARGIN_AMBIGUOUS";
    public static final String RESOLVED_LABEL = "RESOLVED_LABEL";
    public static final String RESOLVED_ALIAS = "RESOLVED_ALIAS";
    public static final String RESOLVED_DESCRIPTION = "RESOLVED_DESCRIPTION";
    public static final String RESOLVED_TEXT = "RESOLVED_TEXT";

    private final String policyVersion;
    private final double absoluteThreshold;
    private final double marginThreshold;
    private final TypeCompatibility typeCompatibility;

    public DefaultResolutionPolicy(String policyVersion, double absoluteThreshold,
                                   double marginThreshold, TypeCompatibility typeCompatibility) {
        this.policyVersion = policyVersion;
        this.absoluteThreshold = absoluteThreshold;
        this.marginThreshold = marginThreshold;
        this.typeCompatibility = typeCompatibility;
    }

    @Override
    public String strategyVersion() {
        return policyVersion;
    }

    @Override
    public ResolutionDecision decide(ResolutionContext context) {
        List<ResolutionCandidate> candidates = sorted(context == null ? null : context.candidates());
        if (candidates.isEmpty()) {
            return decision(context, ResolutionOutcome.UNRESOLVED, NO_CANDIDATES, null, 0, 0, Map.of());
        }
        ResolutionCandidate top = candidates.getFirst();
        double best = top.retrievalScore();
        double runnerUp = candidates.size() > 1 ? candidates.get(1).retrievalScore() : 0;
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("candidate_count", candidates.size());
        diagnostics.put("top_entity_id", top.entityId());
        diagnostics.put("matched_field", top.matchedField());
        if (candidates.size() > 1) {
            diagnostics.put("runner_up_entity_id", candidates.get(1).entityId());
        }

        String proposedType = context == null ? null : context.proposedType();
        if (proposedType == null || proposedType.isBlank()
                || "UNKNOWN".equalsIgnoreCase(proposedType.trim())) {
            return decision(context, ResolutionOutcome.REVIEW_REQUIRED, UNKNOWN_PROPOSED_TYPE,
                    null, best, runnerUp, diagnostics);
        }
        if (!typeCompatibility.compatible(proposedType, top.canonicalType(),
                context.ontologyVersion())) {
            return decision(context, ResolutionOutcome.UNRESOLVED, TYPE_INCOMPATIBLE,
                    null, best, runnerUp, diagnostics);
        }
        if (best < absoluteThreshold) {
            return decision(context, ResolutionOutcome.UNRESOLVED, BELOW_ABSOLUTE_THRESHOLD,
                    null, best, runnerUp, diagnostics);
        }
        if (best - runnerUp < marginThreshold) {
            return decision(context, ResolutionOutcome.AMBIGUOUS, LOW_MARGIN_AMBIGUOUS,
                    null, best, runnerUp, diagnostics);
        }
        return decision(context, ResolutionOutcome.RESOLVED, resolvedReason(top.matchedField()),
                top.entityId(), best, runnerUp, diagnostics);
    }

    private ResolutionDecision decision(ResolutionContext context, ResolutionOutcome outcome,
                                        String reasonCode, String entityId, double best,
                                        double runnerUp, Map<String, Object> diagnostics) {
        String label = entityId == null ? null : labelFor(context, entityId);
        String searchGeneration = context == null || context.candidates() == null ? null
                : context.candidates().stream()
                        .map(ResolutionCandidate::searchGeneration)
                        .filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null);
        return new ResolutionDecision(
                context == null ? null : context.tenantId(),
                context == null ? null : context.sourceVersionId(),
                context == null ? null : context.mentionId(),
                outcome, reasonCode, entityId, label, best, runnerUp,
                policyVersion, context == null ? null : context.ontologyVersion(), diagnostics,
                retrievalStrategyFor(context, entityId), 0L, searchGeneration);
    }

    private static String retrievalStrategyFor(ResolutionContext context, String entityId) {
        if (context == null || entityId == null || context.candidates() == null) {
            return null;
        }
        return context.candidates().stream()
                .filter(candidate -> entityId.equals(candidate.entityId()))
                .map(ResolutionCandidate::retrievalStrategy)
                .findFirst().orElse(null);
    }

    private static String labelFor(ResolutionContext context, String entityId) {
        if (context == null || context.candidates() == null) {
            return null;
        }
        return context.candidates().stream()
                .filter(candidate -> entityId.equals(candidate.entityId()))
                .map(ResolutionCandidate::canonicalLabel)
                .findFirst().orElse(null);
    }

    private static String resolvedReason(String matchedField) {
        String field = matchedField == null ? "" : matchedField.toLowerCase(Locale.ROOT);
        return switch (field) {
            case "label" -> RESOLVED_LABEL;
            case "alias" -> RESOLVED_ALIAS;
            case "description" -> RESOLVED_DESCRIPTION;
            default -> RESOLVED_TEXT;
        };
    }

    private static List<ResolutionCandidate> sorted(List<ResolutionCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<ResolutionCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(ResolutionCandidate::retrievalScore).reversed()
                .thenComparing(ResolutionCandidate::entityId,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }
}
