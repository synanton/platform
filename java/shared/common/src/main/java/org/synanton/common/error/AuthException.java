package org.synanton.common.error;

public class AuthException extends SynantonException {
    public AuthException(String detail) {
        super(detail, "ERR_AUTH_FAILED", 401);
    }
}
