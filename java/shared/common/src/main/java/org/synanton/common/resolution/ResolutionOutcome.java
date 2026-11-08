package org.synanton.common.resolution;

/** Mention-level resolution decision (PR #4a contract). */
public enum ResolutionOutcome {
    RESOLVED,
    AMBIGUOUS,
    UNRESOLVED,
    /** Non-terminal escalation; the version lifecycle becomes PARTIAL. */
    REVIEW_REQUIRED;

    public boolean terminal() {
        return this != REVIEW_REQUIRED;
    }
}
