package org.synanton.common.resolution;

import java.util.List;

/** Validation-only result; 4c.2 never mutates the canonical graph. */
public record RelationValidationResult(
        RelationValidationOutcome outcome,
        String subjectEntityId,
        String predicate,
        String objectEntityId,
        String subjectType,
        String objectType,
        String ontologyVersion,
        List<ValidationViolation> violations,
        String reasonCode) {
    public RelationValidationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }
}
