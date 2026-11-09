package org.synanton.relix.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.common.resolution.ActiveResolution;
import org.synanton.common.resolution.ExtractedRelationView;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.relix.graph.ExtractedMention;
import org.synanton.relix.graph.CanonicalRelationPromotionPort;
import org.synanton.relix.graph.GraphConnector;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GraphWriteControllerAuthTest {

    private static final String SECRET = "relix-test-jwt-secret-at-least-32-bytes";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private GraphConnector connector;
    private CanonicalRelationPromotionPort promoter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        connector = mock(GraphConnector.class);
        promoter = mock(CanonicalRelationPromotionPort.class);
        when(connector.upsertMentions(any())).thenReturn(1);
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "", "/health");
        mvc = MockMvcBuilders.standaloneSetup(new GraphWriteController(connector, promoter))
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

    @Test
    void verifiedTenantMayPersistAResolution() throws Exception {
        ResolutionDecision decision = new ResolutionDecision("tenant-a", "v1", "m1",
                ResolutionOutcome.RESOLVED, "RESOLVED_LABEL", "e1", "Halcyon-7", 0.03, 0.01,
                "v1", "1.0.0", Map.of(), "RRF");
        mvc.perform(post("/graph/resolutions")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(decision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        org.mockito.Mockito.verify(connector).upsertResolution(decision);
    }

    @Test
    void resolutionWithoutTokenIsRejected() throws Exception {
        ResolutionDecision decision = new ResolutionDecision("tenant-a", "v1", "m1",
                ResolutionOutcome.RESOLVED, "RESOLVED_LABEL", "e1", "Halcyon-7", 0.03, 0.01,
                "v1", "1.0.0", Map.of(), "RRF");
        mvc.perform(post("/graph/resolutions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(decision)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resolutionBodyTenantCannotOverrideVerifiedTenant() throws Exception {
        ResolutionDecision decision = new ResolutionDecision("tenant-b", "v1", "m1",
                ResolutionOutcome.RESOLVED, "RESOLVED_LABEL", "e1", "Halcyon-7", 0.03, 0.01,
                "v1", "1.0.0", Map.of(), "RRF");
        mvc.perform(post("/graph/resolutions")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(decision)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activeResolutionsRequireVerifiedTenant() throws Exception {
        when(connector.getActiveResolutions("tenant-a", List.of("m1")))
                .thenReturn(List.of(new ActiveResolution("m1", "e1", "MaintenanceSystem")));
        mvc.perform(post("/v1/resolutions/active")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mentionIds\":[\"m1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].canonicalEntityId").value("e1"));

        mvc.perform(post("/v1/resolutions/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mentionIds\":[\"m1\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void extractedRelationLookupIsTenantVerifiedAnd404Aware() throws Exception {
        when(connector.getExtractedRelation("tenant-a", "r1")).thenReturn(Optional.of(
                new ExtractedRelationView("r1", "tenant-a", "v1", "m1", "m2", "requires", "ref:0", 0, 20)));
        mvc.perform(get("/v1/extracted-relations/r1")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relationId").value("r1"));

        when(connector.getExtractedRelation("tenant-a", "missing")).thenReturn(Optional.empty());
        mvc.perform(get("/v1/extracted-relations/missing")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isNotFound());
    }

    @Test
    void verifiedTenantMayPromoteAValidatedRelation() throws Exception {
        var relation = new org.synanton.common.resolution.CanonicalRelation(
                "tenant-a", "v1", "r1", "m1", "e1", "m2", "e2", "requires", "1.0.0",
                "ref:0", 0, 20);
        when(promoter.promoteRelation(relation)).thenReturn(
                new org.synanton.common.resolution.PromotionResult(1, 1, 1));
        mvc.perform(post("/graph/promotions")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(relation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relations_created").value(1));
    }

    @Test
    void promotionWithoutTokenIsRejected() throws Exception {
        var relation = new org.synanton.common.resolution.CanonicalRelation(
                "tenant-a", "v1", "r1", "m1", "e1", "m2", "e2", "requires", "1.0.0",
                "ref:0", 0, 20);
        mvc.perform(post("/graph/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(relation)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidValidationRowReturnsConflict() throws Exception {
        var relation = new org.synanton.common.resolution.CanonicalRelation(
                "tenant-a", "v1", "r1", "m1", "e1", "m2", "e2", "requires", "1.0.0",
                "ref:0", 0, 20);
        when(promoter.promoteRelation(relation))
                .thenThrow(new IllegalStateException("Relation validation is not VALID: INVALID"));
        mvc.perform(post("/graph/promotions")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(relation)))
                .andExpect(status().isConflict());
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
