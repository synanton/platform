package org.synanton.ingestioncache.domain;

/** Durable lookup identity for one tenant-owned source resource. */
public record SourceIdentityRow(
    String tenantId,
    String connectorId,
    String sourceId,
    String resourceId
) {}
