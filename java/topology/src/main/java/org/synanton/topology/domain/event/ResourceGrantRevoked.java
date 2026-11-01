package org.synanton.topology.domain.event;

import java.util.UUID;

/** Payload of a {@code ResourceGrantRevoked} event: a resource ACL grant was revoked. */
public record ResourceGrantRevoked(UUID grantId, String tenantId) {
}
