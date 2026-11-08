package org.synanton.common.resolution;

import java.util.List;

/** Inputs to a deterministic, versioned resolution policy. */
public record ResolutionContext(
        String tenantId,
        String sourceVersionId,
        String mentionId,
        String surfaceForm,
        String proposedType,
        List<ResolutionCandidate> candidates,
        String ontologyVersion) {
    public ResolutionContext {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
