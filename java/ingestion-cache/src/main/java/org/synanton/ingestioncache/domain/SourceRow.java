package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.Set;

/** Tenant-scoped configured source; discovered resources are stored separately. */
public record SourceRow(
    String tenantId,
    String sourceId,
    String connectorId,
    String connectorType,
    String sourceEndpoint,
    Set<String> capabilityFlags,
    String status,
    Instant createdAt,
    Instant lastSuccessfulSyncAt
) {
    public SourceRow {
        capabilityFlags = Set.copyOf(capabilityFlags);
    }
}
