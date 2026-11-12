package org.synanton.relix.identity;

import org.synanton.common.resolution.GraphIdentity;

import java.time.Instant;

public record CanonicalGraphMetadata(
        String tenantId,
        String graphId,
        String projectionVersion,
        Instant syncedAt,
        Instant createdAt,
        Instant updatedAt) {

    public CanonicalGraphMetadata {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (graphId == null || graphId.isBlank()) {
            throw new IllegalArgumentException("graphId is required");
        }
        if (projectionVersion == null || projectionVersion.isBlank()) {
            throw new IllegalArgumentException("projectionVersion is required");
        }
        if (syncedAt == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("metadata timestamps are required");
        }
    }

    public GraphIdentity identity() {
        return GraphIdentity.canonical(tenantId, projectionVersion);
    }
}
