package org.synanton.topology.domain.event;

/** Payload of a {@code GrantCreated} event: an ACL grant was created. */
public record GrantCreated(String tenantId, String subjectId, String resourceId, String permission) {
}
