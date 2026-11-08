package org.synanton.common.resolution;

/** Relation-level outcome; infrastructure failures are exceptions, never INVALID. */
public enum RelationValidationOutcome {
    VALID,
    INVALID,
    REVIEW_REQUIRED
}
