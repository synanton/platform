package org.synanton.security.issuance;

import java.time.Instant;
import java.util.Set;

public record IssuanceEvent(
        String principalId,
        String tenantId,
        String jti,
        String issuer,
        Instant issuedAt,
        Instant expiresAt,
        Set<String> claimedCapabilities,
        String outcome) {}
