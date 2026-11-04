package org.synanton.synquest.demo;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.synquest.service.KnowledgeSearchBackend;
import org.synanton.synquest.service.KnowledgeSearchBackend.KnowledgeCandidate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoSearchControllerTest {

    private static final String SECRET = "synquest-test-jwt-secret-at-least-32-bytes";
    private static final String QUESTION = "What medications are listed for substance use disorders?";
    private static final UUID REF = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @TempDir Path temp;
    private KnowledgeSearchBackend backend;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        backend = mock(KnowledgeSearchBackend.class);
        var service = new DemoSearchService(backend, mock(IngestionCacheClient.class),
                cache(), temp.resolve("queries.jsonl"));
        mvc = MockMvcBuilders.standaloneSetup(new DemoSearchController(service, backend))
                .addFilters(new TenantContextFilter(new JwtVerifier(SECRET), ""))
                .build();
        Files.writeString(temp.resolve("queries.jsonl"),
                "{\"query_id\":\"q1\",\"question\":\"" + QUESTION + "\"}\n");
    }

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(post("/v1/demo/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownTenantIs404() throws Exception {
        when(backend.hasCanonicalData("tenant-a")).thenReturn(false);
        mvc.perform(post("/v1/demo/search")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"test\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rb010ShapeHasLiteralNullDenseRank() throws Exception {
        when(backend.hasCanonicalData("tenant-a")).thenReturn(true);
        when(backend.isReady("tenant-a")).thenReturn(true);
        when(backend.candidates("tenant-a", QUESTION, 20)).thenReturn(List.of(candidate()));
        mvc.perform(post("/v1/demo/search")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(json)
                            .contains("\"dense_rank\":null")
                            .contains("\"dense_contribution\":0.0")
                            .contains("\"legs_present\":[\"bm25\"]");
                });
    }

    @Test
    void tenantComesFromTokenNotBody() throws Exception {
        when(backend.hasCanonicalData("tenant-a")).thenReturn(true);
        when(backend.isReady("tenant-a")).thenReturn(true);
        when(backend.candidates("tenant-a", QUESTION, 20)).thenReturn(List.of(candidate()));
        mvc.perform(post("/v1/demo/search")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk());
        verify(backend).candidates(eq("tenant-a"), eq(QUESTION), eq(20));
    }

    @Test
    void emptyQueryIs400() throws Exception {
        mvc.perform(post("/v1/demo/search")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void buildingProjectionIs503() throws Exception {
        when(backend.hasCanonicalData("tenant-a")).thenReturn(true);
        when(backend.isReady("tenant-a")).thenReturn(false);
        mvc.perform(post("/v1/demo/search")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void tenantBRoutesToTenantB() throws Exception {
        when(backend.hasCanonicalData("tenant-b")).thenReturn(true);
        when(backend.isReady("tenant-b")).thenReturn(true);
        when(backend.candidates("tenant-b", QUESTION, 20)).thenReturn(List.of(candidate()));
        mvc.perform(post("/v1/demo/search")
                        .header("Authorization", "Bearer " + token("tenant-b"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"" + QUESTION + "\"}"))
                .andExpect(status().isOk());
        verify(backend).candidates(eq("tenant-b"), eq(QUESTION), eq(20));
    }

    private Path cache() throws Exception {
        Path path = temp.resolve("dense.json");
        Files.writeString(path, "{\"chunks\":[{\"id\":\"22222222-2222-4222-8222-222222222222#0\",\"embedding\":[1.0,0.0]}],\"queries\":[{\"query_id\":\"q1\",\"embedding\":[1.0,0.0]}]}");
        return path;
    }

    private static KnowledgeCandidate candidate() {
        return new KnowledgeCandidate(REF + ":0", REF, 0, 1.0, "title", "snippet", "file:///doc",
                Set.of(UUID.randomUUID()), 1, 2, "section", List.of("section"));
    }

    private static String token(String tenantId) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("alice").claim("tenant_id", tenantId)
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
