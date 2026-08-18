package org.synanton.common.validation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.synanton.common.tenant.TenantContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates Jakarta Validation failures to RFC 7807 {@code field_errors[]} payloads.
 */
@RestControllerAdvice
public class ValidationExceptionHandler {

    public static final String TYPE = "https://synanton.dev/errors/validation";

    private final MeterRegistry meterRegistry;
    private final boolean strict;

    public ValidationExceptionHandler(MeterRegistry meterRegistry, ValidationProperties properties) {
        this.meterRegistry = meterRegistry != null ? meterRegistry : Metrics.globalRegistry;
        this.strict = properties == null || properties.strict();
    }

    public ValidationExceptionHandler() {
        this(Metrics.globalRegistry, new ValidationProperties(false, 65536));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationProblem> handleMethodArgument(MethodArgumentNotValidException ex) {
        List<ValidationProblem.FieldError> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fieldError -> {
            String field = fieldError.getField();
            String message = fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "invalid";
            emit(field, message);
            errors.add(new ValidationProblem.FieldError(field, message));
        });
        return problem(errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ValidationProblem> handleConstraint(ConstraintViolationException ex) {
        List<ValidationProblem.FieldError> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String field = violation.getPropertyPath() != null ? violation.getPropertyPath().toString() : "unknown";
            String message = violation.getMessage();
            emit(field, message);
            errors.add(new ValidationProblem.FieldError(field, message));
        }
        return problem(errors);
    }

    private void emit(String field, String error) {
        String tenant = tenant();
        String metric = strict ? "synapt_validation_rejected_total" : "synapt_validation_lenient_warning_total";
        meterRegistry.counter(metric, "tenant", tenant, "field", field, "error", safeError(error)).increment();
    }

    private ResponseEntity<ValidationProblem> problem(List<ValidationProblem.FieldError> errors) {
        ValidationProblem body = new ValidationProblem(TYPE, "Validation failed", 400, errors);
        if (!strict) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
        }
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static String tenant() {
        TenantContext ctx = TenantContext.get();
        return ctx != null && ctx.tenantId() != null ? ctx.tenantId() : "unknown";
    }

    private static String safeError(String error) {
        if (error == null || error.isBlank()) {
            return "invalid";
        }
        return error.length() > 64 ? error.substring(0, 64) : error;
    }
}
