package org.synanton.relix.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.GraphConnector;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GraphWriteControllerAuthTest {

    private static final String SECRET = "relix-test-jwt-secret-at-least-32-bytes";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private GraphConnector connector;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        connector = mock(GraphConnector.class);
        when(connector.upsertMentions(any())).thenReturn(1);
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "", "/health");
        mvc = MockMvcBuilders.standaloneSetup(new GraphWriteController(connector))
                .addFilters(filter)
                .build();
    }

    @Test
    void verifiedTenantMayWriteItsOwnGraph() throws Exception {
        mvc.perform(post("/graph/mentions")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void missingTokenIsRejectedBeforeTheBodyIsTrusted() throws Exception {
        mvc.perform(post("/graph/mentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("tenant-a")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bodyCannotOverrideTheVerifiedTenant() throws Exception {
        mvc.perform(post("/graph/mentions")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("tenant-b")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bodyTenantIsOnlyConfirmatory() throws Exception {
        String token = token("tenant-a");
        assertThat(token).isNotBlank();
        mvc.perform(post("/graph/mentions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("tenant-a")))
                .andExpect(status().isOk());
    }

    private String body(String tenant) throws Exception {
        ExtractedMention mention = new ExtractedMention("m1", tenant, "v1", "ref", "ref:0",
                0, 9, 0, 9, "Halcyon-7", "MaintenanceSystem", Instant.parse("2026-11-08T00:00:00Z"));
        return mapper.writeValueAsString(new GraphWriteController.MentionsRequest(List.of(mention)));
    }

    private static String token(String tenantId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("synflux-extraction")
                .claim("tenant_id", tenantId)
                .jwtID(UUID.randomUUID().toString())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
