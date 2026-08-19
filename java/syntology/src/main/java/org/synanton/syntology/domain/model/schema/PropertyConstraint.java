package org.synanton.syntology.domain.model.schema;

public record PropertyConstraint(
        String pathIri,
        String datatypeIri,
        Integer minCount,
        Integer maxCount
) {
}
