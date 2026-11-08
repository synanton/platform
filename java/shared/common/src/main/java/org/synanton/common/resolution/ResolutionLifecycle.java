package org.synanton.common.resolution;

import java.util.Collection;

/**
 * Version-level resolution lifecycle (PR #4a contract). COMPLETED means every
 * eligible mention reached a terminal outcome (RESOLVED/AMBIGUOUS/UNRESOLVED),
 * not that every mention resolved. Any REVIEW_REQUIRED leaves the version PARTIAL.
 */
public enum ResolutionLifecycle {
    PENDING,
    RESOLVING,
    COMPLETED,
    PARTIAL,
    FAILED;

    public static ResolutionLifecycle derive(Collection<ResolutionOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return COMPLETED;
        }
        return outcomes.stream().allMatch(ResolutionOutcome::terminal) ? COMPLETED : PARTIAL;
    }
}
