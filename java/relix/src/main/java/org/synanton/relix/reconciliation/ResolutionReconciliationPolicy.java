package org.synanton.relix.reconciliation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.ingestioncache.domain.ResolutionResultRow;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.synanton.relix.graph.ResolutionIds;

/**
 * PR #578: state-driven crash reconciliation policy over single-identity graph
 * observations. It never re-evaluates resolution policy and never mutates a
 * stale active edge. It only authorizes a graph re-drive for a persisted
 * RESOLVED decision, or a Cassandra completion from an exact graph identity.
 */
public final class ResolutionReconciliationPolicy {

    private static final TypeReference<Map<String, Object>> DIAGNOSTICS =
            new TypeReference<>() {};
    private final ObjectMapper mapper = new ObjectMapper();

    public ReconciliationAction decide(ResolutionWorkRow work, ResolutionResultRow result,
                                       Optional<GraphResolutionObservation> active,
                                       boolean hasHistory, Instant now) {
        if (work == null || work.mentionId() == null || work.sourceVersionId() == null) {
            return ReconciliationAction.contradiction(null, null, "work identity is incomplete");
        }
        GraphResolutionObservation activeState = active == null ? null : active.orElse(null);
        if (activeState != null && activeState.multipleActive()) {
            return ReconciliationAction.contradiction(null, activeState,
                    "multiple active resolutions for one mention");
        }
        return switch (work.claimStatus() == null ? "" : work.claimStatus()) {
            case "COMPLETED" -> completed(work, result, activeState, hasHistory);
            case "PROCESSING" -> processing(work, activeState, now);
            default -> ReconciliationAction.noop(null);
        };
    }

    private ReconciliationAction completed(ResolutionWorkRow work, ResolutionResultRow result,
                                           GraphResolutionObservation activeState,
                                           boolean hasHistory) {
        if (result == null || result.outcome() == null) {
            return ReconciliationAction.contradiction(null, activeState,
                    "Cassandra work is COMPLETED without a persisted decision");
        }
        ResolutionOutcome outcome;
        try {
            outcome = ResolutionOutcome.valueOf(result.outcome());
        } catch (IllegalArgumentException e) {
            return ReconciliationAction.contradiction(null, activeState,
                    "unknown persisted outcome " + result.outcome());
        }
        if (outcome != ResolutionOutcome.RESOLVED) {
            // Decision inconsistency: detection only, never deletion/supersession.
            return activeState == null
                    ? ReconciliationAction.noop(null)
                    : ReconciliationAction.staleEdge(null, activeState,
                    "non-RESOLVED terminal outcome with an active graph resolution");
        }

        ResolutionDecision decision = decisionFromResult(work, result);
        if (decision.resolvedEntityId() == null || decision.resolvedEntityId().isBlank()
                || decision.strategyVersion() == null) {
            return ReconciliationAction.contradiction(null, activeState,
                    "persisted RESOLVED decision is incomplete");
        }
        String resolutionId = ResolutionIds.of(decision);
        if (activeState == null) {
            if (hasHistory) {
                return ReconciliationAction.contradiction(resolutionId, null,
                        "graph has resolution history but no active edge");
            }
            return ReconciliationAction.repairGraph(resolutionId, decision);
        }
        if (!sameGraphIdentity(work, activeState, decision, resolutionId)) {
            return ReconciliationAction.contradiction(resolutionId, activeState,
                    "active graph resolution does not match the persisted decision identity");
        }
        if (activeState.fencingToken() > work.attemptCount()) {
            return ReconciliationAction.contradiction(resolutionId, activeState,
                    "graph fence is newer than the Cassandra snapshot");
        }
        if (activeState.fencingToken() == work.attemptCount()) {
            return ReconciliationAction.noop(resolutionId);
        }
        return ReconciliationAction.repairGraph(resolutionId, decision);
    }

    private ReconciliationAction processing(ResolutionWorkRow work,
                                            GraphResolutionObservation activeState,
                                            Instant now) {
        boolean expired = work.leaseExpiresAt() != null && work.leaseExpiresAt().isBefore(now);
        if (!expired) {
            return ReconciliationAction.noop(null); // never steal a healthy lease
        }
        if (activeState == null) {
            // No graph commit to recover; the normal lease/consumer retry owns this.
            return ReconciliationAction.noop(null);
        }
        ResolutionDecision decision = decisionFromGraph(work, activeState);
        String resolutionId = ResolutionIds.of(decision);
        if (!sameIdentity(work, activeState, decision, resolutionId)) {
            return ReconciliationAction.contradiction(resolutionId, activeState,
                    "active graph resolution does not match the claim identity/fence");
        }
        return ReconciliationAction.repairCassandra(resolutionId, activeState, decision);
    }

    private boolean sameGraphIdentity(ResolutionWorkRow work, GraphResolutionObservation state,
                                      ResolutionDecision decision, String resolutionId) {
        return Objects.equals(state.sourceVersionId(), work.sourceVersionId().toString())
                && Objects.equals(state.resolutionId(), resolutionId)
                && Objects.equals(state.entityId(), decision.resolvedEntityId());
    }

    private boolean sameIdentity(ResolutionWorkRow work, GraphResolutionObservation state,
                                 ResolutionDecision decision, String resolutionId) {
        return sameGraphIdentity(work, state, decision, resolutionId)
                && state.fencingToken() == work.attemptCount();
    }

    private ResolutionDecision decisionFromResult(ResolutionWorkRow work, ResolutionResultRow result) {
        return new ResolutionDecision(
                work.tenantId(), work.sourceVersionId().toString(), work.mentionId(),
                ResolutionOutcome.valueOf(result.outcome()), result.reasonCode(),
                result.resolvedEntityId(), result.canonicalLabel(), result.bestScore(),
                result.runnerUpScore(), result.strategyVersion(), result.ontologyVersion(),
                diagnostics(result.diagnosticsJson()), null, work.attemptCount());
    }

    private ResolutionDecision decisionFromGraph(ResolutionWorkRow work,
                                                 GraphResolutionObservation state) {
        return new ResolutionDecision(
                work.tenantId(), work.sourceVersionId().toString(), work.mentionId(),
                ResolutionOutcome.RESOLVED, "RECONCILED_FROM_GRAPH", state.entityId(), null,
                state.bestScore(), state.runnerUpScore(), state.policyVersion(),
                state.ontologyVersion(),
                Map.of("reconciled", true, "resolution_id", String.valueOf(state.resolutionId())),
                state.retrievalStrategy(), state.fencingToken());
    }

    private Map<String, Object> diagnostics(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, DIAGNOSTICS);
        } catch (Exception e) {
            return Map.of("diagnostics_parse_error", true);
        }
    }
}
