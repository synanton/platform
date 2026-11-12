package org.synanton.common.resolution;

/** Retryable graph infrastructure/lock failure. */
public class TransientGraphTransactionException extends RetryableProcessingException {

    private static final long serialVersionUID = 1L;

    public TransientGraphTransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
