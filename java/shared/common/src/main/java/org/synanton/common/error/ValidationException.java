package org.synanton.common.error;

public class ValidationException extends SynantonException {
    public ValidationException(String detail) {
        super(detail, "ERR_VALIDATION", 400);
    }
}
