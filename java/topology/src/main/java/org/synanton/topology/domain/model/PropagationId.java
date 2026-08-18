package org.synanton.topology.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PropagationId(UUID grantId, UUID outboxId, String state, Instant createdAt) {
    public static final String PENDING = "PENDING_PROPAGATION";
    public static final String PROPAGATED = "PROPAGATED";
    public static final String STUCK = "STUCK";
}
