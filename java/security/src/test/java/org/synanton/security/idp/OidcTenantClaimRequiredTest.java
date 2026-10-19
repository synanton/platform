package org.synanton.security.idp;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.synanton.common.error.AuthException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live hole closed: an OIDC token without tenant_id used to resolve to the
 * hardcoded "demo" tenant — a silently mis-scoped identity. Tokens must carry
 * the claim; there is no test or demo mode that relied on the fallback.
 */
class OidcTenantClaimRequiredTest {

    private static final String SECRET = "oidc-tenant-claim-test-secret-32bytes";

    private static String token(String tenantClaim) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (tenantClaim != null) {
            claims.claim("tenant_id", tenantClaim);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    /** verifier == null parses without signature verification (the test seam). */
    private final OidcIdentityProvider provider = new OidcIdentityProvider(null, null);

    @Test
    void tokenWithoutTenantClaimIsRejected() throws Exception {
        assertThatThrownBy(() -> provider.resolve("Bearer " + token(null)))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void blankTenantClaimIsRejected() throws Exception {
        assertThatThrownBy(() -> provider.resolve("Bearer " + token("  ")))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("tenant_id");
    }

    @Test
    void tokenWithTenantClaimResolves() throws Exception {
        ValidatedIdentity identity = provider.resolve("Bearer " + token("acme"));
        assertThat(identity.tenantId()).isEqualTo("acme");
        assertThat(identity.subjectId()).isEqualTo("alice");
    }
}
