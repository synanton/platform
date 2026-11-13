package org.synanton.common.resolution;

/** Why an operation is retryable; used for operator-visible outcome labels. */
public enum RetryReason {
    INFRASTRUCTURE,
    TRANSACTION_ABORTED
}
