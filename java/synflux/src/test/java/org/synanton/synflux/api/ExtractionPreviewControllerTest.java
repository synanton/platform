package org.synanton.synflux.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContext;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.synflux.config.ExtractionEngineProperties;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExtractionPreviewControllerTest {

    private static final String SECRET = "synflux-test-jwt-secret-at-least-32-bytes";
    private static final String TEXT = "Halcyon-7 requires a helium leak check.";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<ExtractionContext> seenContext = new AtomicReference<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var adapter = (org.synanton.extraction.engine.ExtractionEngineAdapter) (segment, context) -> {
            seenContext.set(context);
            PreEntity subject = new PreEntity("m1", "Halcyon-7", "MaintenanceSystem",
                    0, 9, TEXT);
            PreEntity object = new PreEntity("m2", "helium leak check", "Concept",
                    19, 36, TEXT);
            return new ExtractionResult(List.of(subject, object),
                    List.of(new RelationCandidate("m1", "requires", "m2", "preview", 0, 37)));
        };
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "", "/health");
        mvc = MockMvcBuilders.standaloneSetup(new ExtractionPreviewController(
                        adapter, new ExtractionEngineProperties("llm", "http://localhost:8000/v1",
                                "qwen3-extraction", 30)))
                .addFilters(filter)
                .build();
    }

    @Test
    void returnsEntitiesRelationsAndEngineMetadata() throws Exception {
        String response = mvc.perform(post("/api/v1/extraction/preview")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ExtractionPreviewController.PreviewRequest(TEXT))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode body = mapper.readTree(response);
        assertThat(body.get("engine").asText()).isEqualTo("llm");
        assertThat(body.get("model").asText()).isEqualTo("qwen3-extraction");
        assertThat(body.get("preEntities").get(0).get("surfaceForm").asText()).isEqualTo("Halcyon-7");
        assertThat(body.get("relations").get(0).get("predicate").asText()).isEqualTo("requires");
        assertThat(seenContext.get().tenantId()).isEqualTo("tenant-a");
        TenantContext.clear();
    }

    @Test
    void requiresAVerifiedTenant() throws Exception {
        mvc.perform(post("/api/v1/extraction/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ExtractionPreviewController.PreviewRequest(TEXT))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsBlankText() throws Exception {
        mvc.perform(post("/api/v1/extraction/preview")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest());
        TenantContext.clear();
    }

    private static String token(String tenantId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("fixture-user")
                .claim("tenant_id", tenantId)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
