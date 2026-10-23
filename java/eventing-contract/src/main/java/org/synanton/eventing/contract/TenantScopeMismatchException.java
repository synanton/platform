package org.synanton.eventing.contract;

/** Raised when a message is presented in a scope other than its declared tenant. */
public final class TenantScopeMismatchException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public TenantScopeMismatchException() {
        super("message tenant does not match the verified tenant scope");
    }
}
