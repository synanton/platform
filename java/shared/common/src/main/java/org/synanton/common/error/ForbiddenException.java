package org.synanton.common.error;

public class ForbiddenException extends SynantonException {
    public ForbiddenException(String detail) {
        super(detail, "ERR_FORBIDDEN", 403);
    }

    public ForbiddenException(String detail, String code) {
        super(detail, code, 403);
    }
}
