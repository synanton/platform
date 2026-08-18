package org.synanton.topology.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.synanton.common.error.NotFoundException;
import org.synanton.common.error.ProblemDetail;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex) {
        return ResponseEntity
                .status(404)
                .body(ProblemDetail.of(404, "Not Found", ex.getMessage(), ex.getCode()));
    }
}
