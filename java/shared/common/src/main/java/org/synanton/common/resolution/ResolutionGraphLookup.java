package org.synanton.common.resolution;

import java.util.List;
import java.util.Optional;

/** Read-only authoritative graph state used by relation validation (4c.2). */
public interface ResolutionGraphLookup {

    default List<ActiveResolution> getActiveResolutions(String tenantId, List<String> mentionIds) {
        throw new UnsupportedOperationException("graph connector does not support active resolution lookup");
    }

    default Optional<ExtractedRelationView> getExtractedRelation(String tenantId, String relationId) {
        throw new UnsupportedOperationException("graph connector does not support extracted-relation lookup");
    }

    /**
     * PR #578: every persisted resolution edge for one mention, including
     * SUPERSEDED history. Used by crash reconciliation; not by policy decisions.
     */
    default List<ResolutionGraphState> getResolutionStates(String tenantId, String mentionId) {
        return List.of();
    }
}
