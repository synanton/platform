package org.synanton.relix.graph;

/** Evidence retirement and derived assertion-state recomputation (#655). */
public interface RelationRetirement {

    void retireSupersededEvidence(String tenantId, String sourceId, long currentGeneration);

    void recomputeAssertionState(String tenantId, String canonicalRelationId);

    /** Derivation-only repair of materialized :REL state for recently changed evidence. */
    int reconcileRetirementState(String tenantId, int limit);
}
