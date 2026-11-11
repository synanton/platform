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

    default GraphCapabilities capabilities() {
        return GraphCapabilities.NONE;
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

    default Optional<GraphResolutionObservation> activeResolution(GraphIdentity identity, ResolutionKey key) {
        requireIdentity(identity, key);
        return activeResolution(key);
    }

    default boolean hasResolutionHistory(GraphIdentity identity, ResolutionKey key) {
        requireIdentity(identity, key);
        return hasResolutionHistory(key);
    }

    default ResolutionWriteOutcome ensureResolution(GraphIdentity identity, ResolutionDecision decision) {
        if (identity == null || !identity.tenantId().equals(decision.tenantId())) {
            throw new IllegalArgumentException("graph identity tenant must match the decision tenant");
        }
        return ensureResolution(decision);
    }

    private static void requireIdentity(GraphIdentity identity, ResolutionKey key) {
        if (identity == null || key == null || !identity.tenantId().equals(key.tenantId())) {
            throw new IllegalArgumentException("graph identity tenant must match the resolution key");
        }
    }
}
