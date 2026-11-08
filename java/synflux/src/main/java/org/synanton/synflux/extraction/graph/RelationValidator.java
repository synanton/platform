package org.synanton.synflux.extraction.graph;

/** Port for Syntology relation-level SHACL/domain-range validation. */
public interface RelationValidator {
    RelationValidation validate(String tenantId, String subjectType, String predicate,
                                String objectType, String ontologyVersion);
}
