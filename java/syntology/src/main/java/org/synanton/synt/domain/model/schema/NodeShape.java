package org.synanton.synt.domain.model.schema;

import java.util.List;

public record NodeShape(
        String iri,
        String targetClassIri,
        List<PropertyConstraint> properties
) {
    public NodeShape {
        properties = properties == null ? List.of() : List.copyOf(properties);
    }
}
