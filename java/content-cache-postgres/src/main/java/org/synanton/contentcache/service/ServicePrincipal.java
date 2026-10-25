package org.synanton.contentcache.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Explicit service principal; never inferred from a missing user or tenant. */
public record ServicePrincipal(
        String principalId,
        Set<ServiceCapability> capabilities,
        Instant expiresAt,
        String issuer) {

    public ServicePrincipal {
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("principalId must not be blank");
        }
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        capabilities = Set.copyOf(capabilities);
    }

    public boolean has(ServiceCapability capability) {
        return capabilities.contains(capability);
    }

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
