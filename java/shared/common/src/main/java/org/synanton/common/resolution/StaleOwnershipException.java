package org.synanton.common.resolution;

/** Terminal: the promotion revision owner was taken over or the set was closed. */
public class StaleOwnershipException extends ContractViolationException {

    private static final long serialVersionUID = 1L;

    public StaleOwnershipException(String message) {
        super(message);
    }
}
