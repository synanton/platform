package org.synanton.security.issuance;

/** Audit failure aborts issuance; no token is returned. */
public class IssuanceAuditException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IssuanceAuditException(String message, Throwable cause) {
        super(message, cause);
    }
}
