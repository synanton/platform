package org.synanton.synflux.resolution;

import org.synanton.common.resolution.RelationValidationResult;

/** Validation-only contract (4c.2); implementations must not mutate the canonical graph. */
public interface CanonicalRelationValidator {
    RelationValidationResult validate(String tenantId, String ontologyVersion, String extractedRelationId);
}
