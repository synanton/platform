package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A single annotation instance attached to a target (typically a chunk) - design §7, §31.
 * Mirrors {@link ChunkRow}'s shape: the high-volume payload lives in Cassandra, while the
 * governance-weight definition/version/processing-run records live in the {@code annotations}
 * Postgres service.
 */
public record AnnotationRow(
    String tenantId,
    String targetType,
    String targetId,
    UUID annotationId,
    String definitionId,
    int definitionVersion,
    String annotationType,
    String namespace,
    String name,
    String value,
    String producer,
    String producerVersion,
    double confidence,
    List<String> sourceClassification,
    String representationUsed,
    String provenance,
    UUID processingRunId,
    Instant createdAt,
    Instant invalidatedAt
) {
    public static final List<String> PUBLIC_ONLY = List.of("PUBLIC");
}
