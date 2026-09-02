package org.synanton.annotations.domain.model;

import java.time.Instant;

/**
 * Identity of an annotation definition (design §8). Immutable once created;
 * its versions carry the actual, independently publishable content.
 */
public record AnnotationDefinition(
        String definitionId,
        String namespace,
        String name,
        String annotationType,
        Instant createdAt
) {
    public static final java.util.Set<String> ANNOTATION_TYPES =
            java.util.Set.of("TAG", "CLASSIFICATION", "ENTITY", "ATTRIBUTE", "SIGNAL");
}
