package org.synanton.topology.domain.event;

/** Payload of a {@code TenantCreated} event: a tenant was registered. */
public record TenantCreated(String tenantId, String displayName, String ownerSubjectId) {
}
