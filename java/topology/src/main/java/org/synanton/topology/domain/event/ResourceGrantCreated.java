package org.synanton.topology.domain.event;

/** Payload of a {@code ResourceGrantCreated} event: a resource ACL grant was created. */
public record ResourceGrantCreated(String tenantId, String subjectId, String resourceId, String permission) {
}
