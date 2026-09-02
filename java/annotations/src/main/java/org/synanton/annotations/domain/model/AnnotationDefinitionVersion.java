package org.synanton.annotations.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * A single version of an annotation definition (design §8, §72). {@code inputs} names
 * the upstream fields/annotations this version reads. Status follows the lifecycle
 * {@code DRAFT -> VALIDATED -> PUBLISHED -> DEPRECATED -> RETIRED}; only a version in
 * {@code DRAFT} or {@code VALIDATED} may still be edited - once {@code PUBLISHED} it is
 * immutable and a new version must be registered instead.
 */
public record AnnotationDefinitionVersion(
        String definitionId,
        int version,
        List<String> inputs,
        String producer,
        String producerVersion,
        String outputType,
        String outputName,
        String status,
        Instant publishedAt,
        Instant createdAt
) {
    public static final String DRAFT = "DRAFT";
    public static final String VALIDATED = "VALIDATED";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String DEPRECATED = "DEPRECATED";
    public static final String RETIRED = "RETIRED";

    /** Versions in this status still accept content edits (design §8). */
    public boolean isMutable() {
        return DRAFT.equals(status) || VALIDATED.equals(status);
    }
}
