package org.synanton.relix.identity;

public record SourceFenceKey(String tenantId, String sourceId) {

    public SourceFenceKey {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId is required");
        }
    }
}
