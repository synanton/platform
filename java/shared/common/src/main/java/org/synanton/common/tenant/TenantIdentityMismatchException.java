package org.synanton.common.tenant;

/** Thrown when request identity and verified tenant identity disagree. */
@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.FORBIDDEN)
public class TenantIdentityMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TenantIdentityMismatchException(String message) {
        super(message);
    }
}
