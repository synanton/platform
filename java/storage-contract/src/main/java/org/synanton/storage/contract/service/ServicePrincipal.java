package org.synanton.storage.contract.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * Token issuance seam (#340): claims select from the compiled capability
     * set. An unknown claim fails here, at issuance, never as a silent
     * zero-row denial at read time.
     */
    public static ServicePrincipal fromClaims(
            String principalId, Set<String> claims, Instant expiresAt, String issuer) {
        Objects.requireNonNull(claims, "claims");
        Set<ServiceCapability> capabilities = claims.stream()
                .map(ServiceCapability::fromClaim)
                .collect(Collectors.toUnmodifiableSet());
        return new ServicePrincipal(principalId, capabilities, expiresAt, issuer);
    }

    public boolean has(ServiceCapability capability) {
        return capabilities.contains(capability);
    }

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
