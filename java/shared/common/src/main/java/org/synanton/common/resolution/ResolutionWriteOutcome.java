package org.synanton.common.resolution;

/** Outcome of an intent-shaped resolution write; no graph state is returned. */
public enum ResolutionWriteOutcome {
    APPLIED,
    REJECTED,
    RETRYABLE
}
