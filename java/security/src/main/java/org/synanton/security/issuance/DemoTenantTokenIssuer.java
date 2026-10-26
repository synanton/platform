package org.synanton.security.issuance;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import org.synanton.storage.contract.service.ServiceCapability;
import org.synanton.storage.contract.service.ServicePrincipal;

/**
 * Demo tenant-bound issuer (#340). Short-lived HS256 tokens only; no rotation,
 * denylist, or refresh flow. Claims map through the closed compiled capability
 * set, and issuance audit is fail-closed: if the audit write throws, no token is
 * returned. Production keys/revocation live in #436.
 */
public final class DemoTenantTokenIssuer {

    public static final long MAX_TTL_SECONDS = 900;

    private final String secret;
    private final String issuer;
    private final String audience;
    private final long ttlSeconds;
    private final IssuanceAudit audit;
    private final LongAdder rejectedIssuances = new LongAdder();

    public DemoTenantTokenIssuer(
            String secret, String issuer, String audience, long ttlSeconds, IssuanceAudit audit) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("demo signing secret must be at least 32 characters");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("audience must not be blank");
        }
        if (ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("demo token TTL must be 1.." + MAX_TTL_SECONDS + " seconds");
        }
        this.secret = secret;
        this.issuer = issuer;
        this.audience = audience;
        this.ttlSeconds = ttlSeconds;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public IssuedToken issue(String principalId, String tenantId, Set<String> capabilityClaims) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);
        ServicePrincipal principal;
        try {
            principal = ServicePrincipal.fromClaims(
                    principalId, capabilityClaims, expiresAt, issuer);
        } catch (RuntimeException rejected) {
            rejectedIssuances.increment();
            throw rejected;
        }
        String jti = UUID.randomUUID().toString();
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(principal.principalId())
                    .issueTime(Date.from(issuedAt))
                    .notBeforeTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(jti)
                    .claim("tenant_id", tenantId)
                    .claim("token_type", "tenant_access")
                    .claim("scope", principal.capabilities().stream()
                            .map(ServiceCapability::claimName)
                            .sorted()
                            .collect(Collectors.joining(" ")))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes()));
            String token = jwt.serialize();

            try {
                audit.record(new IssuanceEvent(
                        principal.principalId(), tenantId, jti, issuer, issuedAt, expiresAt,
                        principal.capabilities().stream().map(ServiceCapability::claimName)
                                .collect(Collectors.toUnmodifiableSet()),
                        "issued"));
            } catch (RuntimeException auditFailure) {
                // The token was signed but must not escape without its audit row.
                throw new IssuanceAuditException("issuance audit failed; token not issued", auditFailure);
            }
            return new IssuedToken(token, principal, jti, issuedAt, expiresAt);
        } catch (JOSEException e) {
            throw new IllegalStateException("demo token signing failed", e);
        }
    }

    /** Rejected issuance attempts (unknown/blank claims); not a token count. */
    public long rejectedIssuances() {
        return rejectedIssuances.sum();
    }

    public record IssuedToken(
            String token,
            ServicePrincipal principal,
            String jti,
            Instant issuedAt,
            Instant expiresAt) {}
}
