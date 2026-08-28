package org.synanton.topology.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ClassGrant(
        UUID grantId,
        String tenantId,
        String subjectKey,
        String subjectType,
        String sensitivityClass,
        String permission,
        String propagationState,
        Instant createdAt
) {}
