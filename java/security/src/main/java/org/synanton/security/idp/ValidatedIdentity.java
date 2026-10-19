package org.synanton.security.idp;

/**
 * @param expiresAt the credential's expiry (JWT/OIDC {@code exp}); null for
 *                  non-expiring credentials such as API keys. Consumers cap
 *                  their identity-cache TTL by this — a cached identity must
 *                  not outlive the credential (#242).
 */
public record ValidatedIdentity(
        String subjectId,
        String tenantId,
        String identityProfile,
        String[] scopes,
        String keyId,
        java.time.Instant expiresAt
) {}
