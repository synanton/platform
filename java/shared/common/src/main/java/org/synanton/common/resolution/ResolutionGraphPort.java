package org.synanton.common.resolution;

import java.time.Instant;
import java.util.Optional;

/**
 * Canonical graph write port for resolution (implemented by Relix).
 * 4c.1 writes :RESOLVES_TO only; canonical relation promotion is separate.
 */
public interface ResolutionGraphPort extends ResolutionGraphLookup {

    default void upsertResolution(ResolutionDecision decision) {
        throw new UnsupportedOperationException("graph connector does not support resolution writes");
    }

    default void supersedeResolution(String tenantId, String sourceVersionId,
                                     String mentionId, Instant supersededAt) {
        throw new UnsupportedOperationException("graph connector does not support resolution supersession");
    }

    /** Whether this adapter implements exact-identity reconciliation operations. */
    default boolean supportsResolutionReconciliation() {
        return false;
    }

    default Optional<GraphResolutionObservation> activeResolution(ResolutionKey key) {
        throw new UnsupportedOperationException("graph connector does not support reconciliation reads");
    }

    default boolean hasResolutionHistory(ResolutionKey key) {
        throw new UnsupportedOperationException("graph connector does not support reconciliation reads");
    }

    default ResolutionWriteOutcome ensureResolution(ResolutionDecision decision) {
        throw new UnsupportedOperationException("graph connector does not support reconciliation writes");
    }
}
