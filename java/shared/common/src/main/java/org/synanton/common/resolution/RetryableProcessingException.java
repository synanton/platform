package org.synanton.common.resolution;

/** Infrastructure failure that must be retried, never treated as "no match". */
public class RetryableProcessingException extends RuntimeException {

    private final RetryReason reason;

    public RetryableProcessingException(String message) {
        this(message, null, RetryReason.INFRASTRUCTURE);
    }

    public RetryableProcessingException(String message, Throwable cause) {
        this(message, cause, RetryReason.INFRASTRUCTURE);
    }

    public RetryableProcessingException(String message, Throwable cause, RetryReason reason) {
        super(message, cause);
        this.reason = reason == null ? RetryReason.INFRASTRUCTURE : reason;
    }

    public RetryReason reason() {
        return reason;
    }
}
