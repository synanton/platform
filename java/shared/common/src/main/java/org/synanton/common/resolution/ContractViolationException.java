package org.synanton.common.resolution;

/** Non-retryable data/contract failure; requires upstream correction. */
public class ContractViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ContractViolationException(String message) {
        super(message);
    }

    public ContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
