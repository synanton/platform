package org.synanton.common.tenant;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.synanton.common.jwt.JwtVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The #350 boundary contract: the verified token's tenant claim is the only
 * authority; a valid token without it fails closed with 401, and the tenant id
 * is exposed to the API layer only through the immutable TenantScope adapter.
 */
class TenantContextFilterTest {

    private static final String SECRET = "tenant-context-filter-test-secret-32bytes";

    private static String token(String tenantId) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)));
        if (tenantId != null) {
            claims.claim("tenant_id", tenantId);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static MockHttpServletResponse run(String token) throws Exception {
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/search");
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chained.set(true);
        filter.doFilterInternal(request, response, chain);
        assertThat(chained.get()).as("filter must not admit an unverified request").isFalse();
        return response;
    }

    @Test
    void validTokenWithoutTenantClaimFailsClosedAs401() throws Exception {
        MockHttpServletResponse response = run(token(null));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("TENANT_CONTEXT_MISSING");
    }

    @Test
    void missingBearerFailsClosedAs401() throws Exception {
        MockHttpServletResponse response = run(null);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTHENTICATION_REQUIRED");
    }

    @Test
    void verifiedTenantIsExposedToTheApiLayerOnly() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/search");
        request.addHeader("Authorization", "Bearer " + token("tenant-a"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> {
            TenantContext ctx = TenantContext.get();
            seen[0] = ctx == null ? null : ctx.requireTenantId();
        };
        filter.doFilterInternal(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen[0]).isEqualTo("tenant-a");
        assertThat(TenantContext.get()).as("context cleared after the request").isNull();
    }

    @Test
    void scopeAdapterRejectsAnEmptyBoundaryTenant() {
        TenantContext.setAnonymous("");
        try {
            assertThatThrownBy(() -> TenantContext.get().requireTenantId())
                    .isInstanceOf(MissingTenantContextException.class);
        } finally {
            TenantContext.clear();
        }
    }
}
