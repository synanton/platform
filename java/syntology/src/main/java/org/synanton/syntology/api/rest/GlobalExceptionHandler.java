package org.synanton.syntology.api.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.synanton.common.error.ForbiddenException;
import org.synanton.common.error.ProblemDetail;
import org.synanton.syntology.infra.jena.JenaTdb2Adapter;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(JenaTdb2Adapter.NotFoundException.class)
    ResponseEntity<org.springframework.http.ProblemDetail> handleNotFound(JenaTdb2Adapter.NotFoundException ex) {
        var detail = org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        detail.setTitle("Not Found");
        return ResponseEntity.status(404).body(detail);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<org.springframework.http.ProblemDetail> handleIllegalState(IllegalStateException ex) {
        var detail = org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Bad Request");
        return ResponseEntity.status(400).body(detail);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<org.springframework.http.ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        var detail = org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        detail.setTitle("Bad Request");
        return ResponseEntity.status(400).body(detail);
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ProblemDetail> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(403)
                .body(ProblemDetail.of(403, "Forbidden", ex.getMessage(), ex.getCode()));
    }
}
