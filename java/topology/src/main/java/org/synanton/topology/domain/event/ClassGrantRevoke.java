package org.synanton.topology.domain.event;

import java.util.UUID;

/** Payload of a {@code ClassGrantRevoke} event: a sensitivity-class grant was revoked. */
public record ClassGrantRevoke(UUID grantId, String tenantId) {
}
