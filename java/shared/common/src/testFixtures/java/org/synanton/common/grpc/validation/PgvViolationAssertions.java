package org.synanton.common.grpc.validation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertion helpers for PGV field violations.
 */
public final class PgvViolationAssertions {

    private PgvViolationAssertions() {}

    public static void assertRejectedField(List<PgvFieldViolation> violations, String field, String error) {
        assertThat(violations)
                .as("expected a violation on %s with error %s", field, error)
                .anySatisfy(violation -> {
                    assertThat(violation.field()).isEqualTo(field);
                    assertThat(violation.error()).isEqualTo(error);
                });
    }
}
