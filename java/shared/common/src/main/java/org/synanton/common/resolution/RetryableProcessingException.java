package org.synanton.common.resolution;

/** Infrastructure failure that must be retried, never treated as "no match". */
public class RetryableProcessingException extends RuntimeException {

    public RetryableProcessingException(String message) {
        super(message);
    }

    public RetryableProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
