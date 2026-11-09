package org.synanton.relix.reconciliation;

import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionGraphState;

/** A reconciliation decision plus the exact graph identity it was based on. */
public record ReconciliationAction(
        ReconciliationKind kind,
        String resolutionId,
        ResolutionGraphState activeState,
        ResolutionDecision resolutionDecision,
        String detail) {

    public static ReconciliationAction noop(String resolutionId) {
        return new ReconciliationAction(ReconciliationKind.NOOP, resolutionId, null, null, "consistent");
    }

    public static ReconciliationAction repairGraph(String resolutionId, ResolutionDecision decision) {
        return new ReconciliationAction(ReconciliationKind.REPAIR_GRAPH, resolutionId, null,
                decision, "graph write missing or stale");
    }

    public static ReconciliationAction repairCassandra(String resolutionId,
                                                       ResolutionGraphState state,
                                                       ResolutionDecision decision) {
        return new ReconciliationAction(ReconciliationKind.REPAIR_CASSANDRA, resolutionId, state,
                decision, "graph committed; Cassandra completion missing");
    }

    public static ReconciliationAction staleEdge(String resolutionId, ResolutionGraphState state,
                                                 String detail) {
        return new ReconciliationAction(ReconciliationKind.STALE_EDGE, resolutionId, state, null,
                detail);
    }

    public static ReconciliationAction contradiction(String resolutionId, ResolutionGraphState state,
                                                     String detail) {
        return new ReconciliationAction(ReconciliationKind.CONTRADICTION, resolutionId, state, null,
                detail);
    }
}
