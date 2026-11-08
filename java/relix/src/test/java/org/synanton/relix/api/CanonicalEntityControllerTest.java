package org.synanton.relix.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.resolution.CanonicalEntityLookup;
import org.synanton.common.tenant.TenantContextFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CanonicalEntityControllerTest {

    private static final String SECRET = "relix-test-jwt-secret-at-least-32-bytes";
    private CanonicalEntityLookup lookup;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        lookup = mock(CanonicalEntityLookup.class);
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "", "/health");
        mvc = MockMvcBuilders.standaloneSetup(new CanonicalEntityController(lookup))
                .addFilters(filter)
                .build();
    }

    @Test
    void verifiedTenantGetsTheActiveType() throws Exception {
        when(lookup.getActiveEntityType("tenant-a", "e1")).thenReturn(Optional.of("MaintenanceSystem"));
        mvc.perform(get("/v1/entities/e1/type")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("MaintenanceSystem"));
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        mvc.perform(get("/v1/entities/e1/type")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingEntityIs404() throws Exception {
        when(lookup.getActiveEntityType("tenant-a", "e1")).thenReturn(Optional.empty());
        mvc.perform(get("/v1/entities/e1/type")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isNotFound());
    }

    private static String token(String tenantId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("synquest-resolution")
                .claim("tenant_id", tenantId)
                .jwtID(UUID.randomUUID().toString())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
