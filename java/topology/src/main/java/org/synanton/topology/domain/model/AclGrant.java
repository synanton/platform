package org.synanton.topology.domain.model;

import java.util.UUID;

public record AclGrant(
        UUID grantId,
        UUID subjectId,
        String subjectType,
        String resourcePath,
        String permission,
        String source
) {}
