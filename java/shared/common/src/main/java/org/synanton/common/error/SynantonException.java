package org.synanton.common.error;

public class SynantonException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public SynantonException(String message, String code, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public SynantonException(String message, String code, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
}
