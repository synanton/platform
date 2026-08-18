package org.synanton.common.error;

public class NotFoundException extends SynantonException {
    public NotFoundException(String detail) {
        super(detail, "ERR_NOT_FOUND", 404);
    }
}
