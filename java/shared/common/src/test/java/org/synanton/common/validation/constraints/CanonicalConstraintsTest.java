package org.synanton.common.validation.constraints;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalConstraintsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptCanonicalIdentifiers() {
        Sample sample = new Sample(
                "demo-tenant",
                "11111111-1111-1111-1111-111111111111",
                "idem-1",
                "who supplies Acme?",
                "Acme Corp"
        );
        assertThat(validator.validate(sample)).isEmpty();
    }

    @Test
    void shouldRejectInvalidTenantId() {
        Sample sample = new Sample("bad tenant!", "11111111-1111-1111-1111-111111111111", "k", "q", "n");
        Set<ConstraintViolation<Sample>> violations = validator.validate(sample);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("tenantId"));
    }

    record Sample(
            @TenantId String tenantId,
            @ResourceId String resourceId,
            @IdempotencyKey String idempotencyKey,
            @QueryText String query,
            @FreeText String displayName
    ) {}
}
