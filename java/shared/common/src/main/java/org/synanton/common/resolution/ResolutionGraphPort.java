package org.synanton.common.resolution;

import java.time.Instant;

/**
 * Canonical graph write port for resolution (implemented by Relix in 4c).
 * 4a defines the contract only; no :RESOLVES_TO writes exist yet.
 */
public interface ResolutionGraphPort {
    void upsertResolution(ResolutionDecision decision);

    void supersedeResolution(String tenantId, String sourceVersionId, String mentionId, Instant supersededAt);
}
