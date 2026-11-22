package org.synanton.common.resolution;

/** Terminal: Cassandra no longer accepts the operation's generation. */
public class UnauthorizedGenerationException extends ContractViolationException {

    private static final long serialVersionUID = 1L;

    public UnauthorizedGenerationException(String message) {
        super(message);
    }
}
