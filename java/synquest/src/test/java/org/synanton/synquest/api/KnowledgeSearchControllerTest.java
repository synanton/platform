package org.synanton.synquest.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.synquest.service.KnowledgeSearchBackend;
import org.synanton.synquest.service.KnowledgeSearchBackend.KnowledgeCandidate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public search query path (frozen search-model, Gen-A): tenant and
 * principal come only from the verified identity context — never from the body;
 * results carry the frozen fields and no backend types.
 */
class KnowledgeSearchControllerTest {

    private static final String SECRET = "synquest-test-jwt-secret-at-least-32-bytes";

    private KnowledgeSearchBackend backend;
    private org.synanton.synquest.service.SearchService searchService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        backend = mock(KnowledgeSearchBackend.class);
        searchService = mock(org.synanton.synquest.service.SearchService.class);
        var controller = new KnowledgeSearchController(backend, searchService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new TenantContextFilter(new JwtVerifier(SECRET), ""))
                .build();
    }

    private static String token(String tenantId) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .claim("tenant_id", tenantId)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static KnowledgeSearchBackend.KnowledgeCandidate candidate(String tenantId) {
        UUID contentRefId = UUID.nameUUIDFromBytes(("chunk:" + tenantId).getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        return new KnowledgeCandidate(contentRefId + ":0", contentRefId, 0, 0.42,
                "Supply chain", "The supply-chain policy governs logistics.", "file:///doc.md",
                Set.of(UUID.nameUUIDFromBytes(("version:" + tenantId).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void searchBindsTenantFromVerifiedJwtAndReturnsFrozenShape() throws Exception {
        var candidate = candidate("tenant-a");
        when(backend.isReady("tenant-a")).thenReturn(true);
        when(backend.strategy()).thenReturn("bm25");
        when(searchService.stats("tenant-a"))
                .thenReturn(new org.synanton.synquest.api.dto.IndexStats("tenant-a", 1, 7L, "GREEN"));
        when(backend.candidates(eq("tenant-a"), eq("supply-chain"), eq(20)))
                .thenReturn(List.of(candidate));

        mvc.perform(post("/v1/search")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"supply-chain\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String json = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(json)
                            .contains("\"object_id\":\"" + candidate.objectId() + "\"")
                            .contains("\"provenance_ref\":\"/v1/knowledge/" + candidate.objectId() + "/provenance\"")
                            .contains("\"strategy\":\"bm25\"")
                            .contains("\"configuration_generation\":7")
                            .contains("\"source_version_ids\"");
                    // Backend topology must not leak into the public contract.
                    org.assertj.core.api.Assertions.assertThat(json)
                            .doesNotContain("score_dense", "rank_dense", "structured_content", "ingest_usage");
                });

        verify(backend).candidates(eq("tenant-a"), eq("supply-chain"), eq(20));
    }

    @Test
    void tenantInRequestBodyIsIgnoredTenantComesOnlyFromVerifiedContext() throws Exception {
        var candidate = candidate("tenant-b");
        when(backend.isReady("tenant-a")).thenReturn(true);
        when(backend.strategy()).thenReturn("bm25");
        when(searchService.stats("tenant-a"))
                .thenReturn(new org.synanton.synquest.api.dto.IndexStats("tenant-a", 1, 7L, "GREEN"));
        when(backend.candidates(eq("tenant-a"), eq("supply-chain"), eq(20)))
                .thenReturn(List.of(candidate));

        // The body names tenant-b; the verified context (tenant-a) wins.
        mvc.perform(post("/v1/search")
                        .header("Authorization", "Bearer " + token("tenant-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant\":\"tenant-b\",\"query\":\"supply-chain\"}"))
                .andExpect(status().isOk());

        verify(backend).candidates(eq("tenant-a"), eq("supply-chain"), eq(20));
        verify(backend, never()).candidates(eq("tenant-b"), eq("supply-chain"), eq(20));
    }

    @Test
    void missingBearerTokenIsRejected() throws Exception {
        mvc.perform(post("/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"supply-chain\"}"))
                .andExpect(status().isUnauthorized());
        verify(backend, never()).candidates(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
