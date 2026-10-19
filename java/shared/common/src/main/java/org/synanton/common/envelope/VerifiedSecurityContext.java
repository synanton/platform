package org.synanton.common.envelope;

import java.time.Instant;

/**
 * Representation of validated identity claims from a verified security
 * envelope. Only instances returned by a successful
 * {@link EnvelopeVerifier#verify} may be bound to request context.
 */
public record VerifiedSecurityContext(
        String tenantId,
        String subjectId,
        String issuer,
        String audience,
        Instant expiresAt
) {
    public VerifiedSecurityContext {
        if (tenantId == null || tenantId.isBlank())
            throw new IllegalArgumentException("tenantId required");
        if (subjectId == null || subjectId.isBlank())
            throw new IllegalArgumentException("subjectId required");
    }
}
