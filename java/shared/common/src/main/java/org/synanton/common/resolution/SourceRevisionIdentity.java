package org.synanton.common.resolution;

/** Fence input for a source revision graph mutation. */
public record SourceRevisionIdentity(String tenantId, String sourceId, long generation) {

    public SourceRevisionIdentity {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId is required");
        }
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }
}
