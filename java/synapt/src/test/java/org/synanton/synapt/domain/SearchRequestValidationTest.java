package org.synanton.synapt.domain;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validRequestPassesValidation() {
        SearchRequest req = new SearchRequest("who supplies Acme?", 10, null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void blankQueryFailsValidation() {
        SearchRequest req = new SearchRequest("", 10, null);
        Set<ConstraintViolation<SearchRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> "query".equals(v.getPropertyPath().toString()));
    }

    @Test
    void nullQueryFailsValidation() {
        SearchRequest req = new SearchRequest(null, 10, null);
        Set<ConstraintViolation<SearchRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> "query".equals(v.getPropertyPath().toString()));
    }

    @Test
    void queryAtMaxLengthPassesValidation() {
        SearchRequest req = new SearchRequest("Q".repeat(10000), 10, null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void queryExceedingMaxLengthFailsValidation() {
        SearchRequest req = new SearchRequest("Q".repeat(10001), 10, null);
        Set<ConstraintViolation<SearchRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> "query".equals(v.getPropertyPath().toString()));
    }

    @Test
    void topKAtMaxBoundPassesValidation() {
        SearchRequest req = new SearchRequest("query", 100, null);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void topKExceedingMaxFailsValidation() {
        SearchRequest req = new SearchRequest("query", 101, null);
        Set<ConstraintViolation<SearchRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> "topK".equals(v.getPropertyPath().toString()));
    }

    @Test
    void topKBelowMinFailsValidation() {
        SearchRequest req = new SearchRequest("query", 0, null);
        Set<ConstraintViolation<SearchRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> "topK".equals(v.getPropertyPath().toString()));
    }

    @Test
    void nullTopKIsAllowed() {
        SearchRequest req = new SearchRequest("query", null, null);
        assertThat(validator.validate(req)).isEmpty();
        assertThat(req.effectiveTopK()).isEqualTo(10);
    }
}
