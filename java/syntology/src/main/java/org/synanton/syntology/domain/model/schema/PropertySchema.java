package org.synanton.syntology.domain.model.schema;

public record PropertySchema(
        String name,
        String path,
        String datatype,
        Integer minCount,
        Integer maxCount
) {
}
