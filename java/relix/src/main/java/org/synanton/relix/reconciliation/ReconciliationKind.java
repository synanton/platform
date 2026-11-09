package org.synanton.relix.reconciliation;

/** Exact action selected by the state-driven reconciliation policy (PR #578). */
public enum ReconciliationKind {
    NOOP,
    REPAIR_GRAPH,
    REPAIR_CASSANDRA,
    STALE_EDGE,
    CONTRADICTION
}
