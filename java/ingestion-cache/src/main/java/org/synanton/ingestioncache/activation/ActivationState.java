package org.synanton.ingestioncache.activation;

import java.util.EnumSet;
import java.util.Set;

/** ADR-029 lifecycle state machine. Transitions are explicit; replay is idempotent. */
public enum ActivationState {
    REVISION_ACCEPTED,
    EXTRACTING,
    EXTRACTION_COMPLETE,
    GRAPH_COMMITTING,
    GRAPH_COMMITTED,
    SEARCH_COMMITTING,
    SEARCH_COMMITTED,
    ACTIVATING,
    CURRENT,
    RETIRING,
    RETIRED,
    EXTRACTION_FAILED,
    EXTRACTION_PARTIAL,
    GRAPH_COMMIT_FAILED,
    SEARCH_COMMIT_FAILED,
    STALE_REJECTED,
    ACTIVATION_PARTIAL,
    FAILED;

    public Set<ActivationState> allowedNext() {
        return switch (this) {
            case REVISION_ACCEPTED -> EnumSet.of(EXTRACTING, EXTRACTION_FAILED, STALE_REJECTED);
            case EXTRACTING -> EnumSet.of(EXTRACTION_COMPLETE, EXTRACTION_FAILED, EXTRACTION_PARTIAL, STALE_REJECTED);
            case EXTRACTION_COMPLETE -> EnumSet.of(GRAPH_COMMITTING, GRAPH_COMMIT_FAILED, STALE_REJECTED);
            case GRAPH_COMMITTING -> EnumSet.of(GRAPH_COMMITTED, GRAPH_COMMIT_FAILED, STALE_REJECTED);
            case GRAPH_COMMITTED -> EnumSet.of(SEARCH_COMMITTING, SEARCH_COMMIT_FAILED, STALE_REJECTED);
            case SEARCH_COMMITTING -> EnumSet.of(SEARCH_COMMITTED, SEARCH_COMMIT_FAILED, STALE_REJECTED);
            case SEARCH_COMMITTED -> EnumSet.of(ACTIVATING, ACTIVATION_PARTIAL, STALE_REJECTED);
            case ACTIVATING -> EnumSet.of(CURRENT, ACTIVATION_PARTIAL, STALE_REJECTED);
            case CURRENT -> EnumSet.of(RETIRING);
            case RETIRING -> EnumSet.of(RETIRED);
            case EXTRACTION_FAILED, EXTRACTION_PARTIAL -> EnumSet.of(EXTRACTING, STALE_REJECTED);
            case GRAPH_COMMIT_FAILED -> EnumSet.of(GRAPH_COMMITTING, STALE_REJECTED);
            case SEARCH_COMMIT_FAILED -> EnumSet.of(SEARCH_COMMITTING, STALE_REJECTED);
            case ACTIVATION_PARTIAL -> EnumSet.of(ACTIVATING, SEARCH_COMMITTED, STALE_REJECTED);
            case FAILED -> EnumSet.of(STALE_REJECTED);
            case STALE_REJECTED, RETIRED -> EnumSet.noneOf(ActivationState.class);
        };
    }

    public boolean canTransitionTo(ActivationState next) {
        return next == this || allowedNext().contains(next);
    }
}
