package org.synanton.relix.reconciliation;

import java.util.Optional;
import org.synanton.common.resolution.ResolutionDecision;

/**
 * Transitional #585 seam: single-identity observations + intent verbs only.
 * Delete this seam (do not evolve it) when #585 migrates onto #587 contracts.
 * Migration #590; Neo4j-only deviation #591.
 */
public interface ResolutionReconciliationGraph {

    /**
     * Active graph state for exactly one mention identity, if present. The key is
     * mention-scoped; policy compares the observed source version and identity.
     */
    Optional<GraphResolutionObservation> activeResolution(ResolutionKey key);

    /** Whether any resolution edge (active or superseded) exists for one mention identity. */
    boolean hasResolutionHistory(ResolutionKey key);

    /** Ensure the persisted decision exists at its identity + fence; returns only the outcome. */
    ResolutionWriteOutcome ensureResolution(ResolutionDecision intent);
}
