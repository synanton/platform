package org.synanton.topology.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Grant(
        UUID grantId,
        String tenantId,
        String subjectId,
        String subjectType,
        String resourcePath,
        String permission,
        String source,
        Instant createdAt
) {}
