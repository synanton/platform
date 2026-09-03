package org.synanton.annotations.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.synanton.common.error.ProblemDetail;
import org.synanton.common.error.SynantonException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SynantonException.class)
    public ResponseEntity<ProblemDetail> handleSynantonException(SynantonException ex) {
        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(ProblemDetail.of(ex.getHttpStatus(), ex.getCode(), ex.getMessage(), ex.getCode()));
    }
}
