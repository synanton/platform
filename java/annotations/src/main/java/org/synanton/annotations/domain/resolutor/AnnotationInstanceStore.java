package org.synanton.annotations.domain.resolutor;

import java.util.List;

/**
 * Port to the high-volume annotation instances (AAP-1's Cassandra {@code annotations}
 * table, in {@code ingestion-cache}). Kept as an interface so Resolutor's dependency-graph
 * logic can be unit-tested without a running Cassandra cluster.
 */
public interface AnnotationInstanceStore {

    /** Every currently-valid (non-invalidated) target annotated by this definition, for this tenant. */
    List<TargetRef> findTargets(String tenantId, String definitionId);

    /** Marks the instance(s) of {@code definitionId}@{@code version} on this target superseded, not deleted. */
    void invalidate(String tenantId, String targetType, String targetId, String definitionId, int version);

    record TargetRef(String targetType, String targetId) {}
}
