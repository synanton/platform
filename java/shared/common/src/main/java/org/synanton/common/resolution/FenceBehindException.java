package org.synanton.common.resolution;

/** Retryable: the Neo4j fence has not reached the operation generation yet. */
public class FenceBehindException extends RetryableProcessingException {

    private static final long serialVersionUID = 1L;

    public FenceBehindException(String message) {
        super(message);
    }
}
