package org.synanton.storage.contract.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Capability-bearing service principal. The constructor is private; the only
 * production producer is {@link #fromClaims}, restricted by
 * {@code ServicePrincipalBoundaryTest} to the security issuance package.
 * Deserialization cannot construct a principal with capabilities.
 */
public final class ServicePrincipal {

    private final String principalId;
    private final Set<ServiceCapability> capabilities;
    private final Instant expiresAt;
    private final String issuer;

    private ServicePrincipal(
            String principalId,
            Set<ServiceCapability> capabilities,
            Instant expiresAt,
            String issuer) {
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("principalId must not be blank");
        }
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        this.principalId = principalId;
        this.capabilities = Set.copyOf(capabilities);
        this.expiresAt = expiresAt;
        this.issuer = issuer;
    }

    /**
     * Token issuance seam (#340): claims select from the compiled capability
     * set. An unknown claim fails here, at issuance, never as a silent
     * zero-row denial at read time. Production callers are restricted to the
     * security issuance package by ServicePrincipalBoundaryTest.
     */
    public static ServicePrincipal fromClaims(
            String principalId, Set<String> claims, Instant expiresAt, String issuer) {
        Objects.requireNonNull(claims, "claims");
        Set<ServiceCapability> capabilities = claims.stream()
                .map(ServiceCapability::fromClaim)
                .collect(Collectors.toUnmodifiableSet());
        return new ServicePrincipal(principalId, capabilities, expiresAt, issuer);
    }

    public String principalId() {
        return principalId;
    }

    public Set<ServiceCapability> capabilities() {
        return capabilities;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public String issuer() {
        return issuer;
    }

    public boolean has(ServiceCapability capability) {
        return capabilities.contains(capability);
    }

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ServicePrincipal that)) {
            return false;
        }
        return principalId.equals(that.principalId)
                && capabilities.equals(that.capabilities)
                && expiresAt.equals(that.expiresAt)
                && issuer.equals(that.issuer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(principalId, capabilities, expiresAt, issuer);
    }

    @Override
    public String toString() {
        return "ServicePrincipal[principalId=" + principalId
                + ", capabilities=" + capabilities
                + ", expiresAt=" + expiresAt
                + ", issuer=" + issuer + "]";
    }
}
