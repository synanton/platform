package org.synanton.common.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.synanton.common.error.AuthException;

import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifies HS256 JWTs signed with SYNANTON_JWT_SECRET.
 * Thread-safe; create one instance and reuse it.
 */
public class JwtVerifier {

    private final byte[] secret;

    public JwtVerifier(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("SYNANTON_JWT_SECRET must be at least 32 characters");
        }
        this.secret = secret.getBytes();
    }

    public SubjectAssertion verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);

            if (!jwt.getHeader().getAlgorithm().equals(JWSAlgorithm.HS256)) {
                throw new AuthException("Unsupported JWT algorithm");
            }

            JWSVerifier verifier = new MACVerifier(secret);
            if (!jwt.verify(verifier)) {
                throw new AuthException("Invalid JWT signature");
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            Date exp = claims.getExpirationTime();
            if (exp == null || exp.toInstant().isBefore(Instant.now())) {
                throw new AuthException("JWT expired");
            }

            String subject = claims.getSubject();
            String tenantId = claims.getStringClaim("tenant_id");
            Object uidClaim = claims.getClaim("uid");
            int uid = uidClaim instanceof Number number ? number.intValue() : -1;

            @SuppressWarnings("unchecked")
            List<Number> rawGids = (List<Number>) claims.getClaim("gid");
            List<Integer> gids = rawGids == null
                    ? List.of()
                    : rawGids.stream().map(Number::intValue).toList();

            Set<String> roles = new HashSet<>();
            List<String> roleClaim = claims.getStringListClaim("roles");
            if (roleClaim != null) {
                roles.addAll(roleClaim);
            }
            String identityProfile = claims.getStringClaim("identity_profile");
            String assertionId = claims.getJWTID();

            return new SubjectAssertion(
                    subject,
                    uid,
                    gids,
                    tenantId,
                    exp.toInstant(),
                    roles,
                    identityProfile,
                    assertionId
            );

        } catch (ParseException e) {
            throw new AuthException("Malformed JWT");
        } catch (JOSEException e) {
            throw new AuthException("JWT verification error: " + e.getMessage());
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("JWT processing failed: " + e.getMessage());
        }
    }
}
