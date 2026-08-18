package org.synanton.security.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.synanton.common.error.AuthException;
import org.synanton.common.error.ProblemDetail;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ProblemDetail> handleAuth(AuthException ex) {
        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(ProblemDetail.of(ex.getHttpStatus(), "Authentication Failed", ex.getMessage(), ex.getCode()));
    }
}
