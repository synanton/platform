package org.synanton.common.resolution;

/**
 * Explicit classification of every consumer failure path (PR #4d.1).
 *
 * <p>Infrastructure failures are retryable; contract violations require
 * correction; stale fences are rejected work; legitimate policy outcomes are
 * not failures at all.
 */
public enum FailureClass {
    INFRASTRUCTURE_RETRYABLE,
    CONTRACT_VIOLATION,
    LEGITIMATE_OUTCOME,
    STALE_REJECT
}
