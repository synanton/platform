package org.synanton.common.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Mints short-lived HS256 service tokens for tenant-scoped internal calls. */
public final class ServiceTokenMinter {

    private final byte[] secret;

    public ServiceTokenMinter(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("SYNANTON_JWT_SECRET must be at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String mint(String tenantId, String subject) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("tenant_id", tenantId)
                    .jwtID(UUID.randomUUID().toString())
                    .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not mint service JWT", e);
        }
    }
}
