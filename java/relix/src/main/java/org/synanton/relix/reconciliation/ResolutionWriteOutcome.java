package org.synanton.relix.reconciliation;

/** Outcome of an intent-shaped resolution write; no graph state is returned. */
public enum ResolutionWriteOutcome {
    APPLIED,
    REJECTED,
    RETRYABLE
}
