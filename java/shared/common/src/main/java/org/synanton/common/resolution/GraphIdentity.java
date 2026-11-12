package org.synanton.common.resolution;

/**
 * Logical graph identity. The tenant, logical graph, and projection version are
 * deliberately distinct: a cache/read-replica or an alternative backend must not
 * collapse them into a single string.
 */
public record GraphIdentity(String tenantId, String graphId, String projectionVersion) {

    public GraphIdentity {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (graphId == null || graphId.isBlank()) {
            throw new IllegalArgumentException("graphId is required");
        }
        if (projectionVersion == null || projectionVersion.isBlank()) {
            throw new IllegalArgumentException("projectionVersion is required");
        }
    }

    public static GraphIdentity canonical(String tenantId, String projectionVersion) {
        return new GraphIdentity(tenantId, "canonical", projectionVersion);
    }
}
