package org.synanton.common.resolution;

import java.time.Instant;

/**
 * Canonical graph write port for resolution (implemented by Relix).
 * 4c.1 writes :RESOLVES_TO only; canonical relation promotion is separate.
 */
public interface ResolutionGraphPort {

    default void upsertResolution(ResolutionDecision decision) {
        throw new UnsupportedOperationException("graph connector does not support resolution writes");
    }

    default void supersedeResolution(String tenantId, String sourceVersionId,
                                     String mentionId, Instant supersededAt) {
        throw new UnsupportedOperationException("graph connector does not support resolution supersession");
    }
}
