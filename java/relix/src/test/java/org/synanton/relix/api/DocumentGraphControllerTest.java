package org.synanton.relix.api;

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
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.relix.api.dto.DocumentGraphResponse;
import org.synanton.relix.graph.GraphConnector;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentGraphControllerTest {

    private static final String SECRET = "relix-test-jwt-secret-at-least-32-bytes";
    private GraphConnector graph;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        graph = mock(GraphConnector.class);
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "", "/health");
        mvc = MockMvcBuilders.standaloneSetup(new DocumentGraphController(graph))
                .addFilters(filter)
                .build();
    }

    @Test
    void returnsDocumentScopedGraph() throws Exception {
        when(graph.documentGraph("tenant-a", "source-version-1")).thenReturn(
                new DocumentGraphResponse("source-version-1", "source-version-1",
                        List.of(new DocumentGraphResponse.Node("m1", "Acme", "Supplier",
                                "c:0", 0, 4, 10, 14, "UNRESOLVED", null, "gen-1")),
                        List.of(), false));

        mvc.perform(get("/v1/documents/source-version-1/graph")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document_id").value("source-version-1"))
                .andExpect(jsonPath("$.source_version_id").value("source-version-1"))
                .andExpect(jsonPath("$.nodes[0].mention_id").value("m1"))
                .andExpect(jsonPath("$.nodes[0].extraction_generation").value("gen-1"));
    }

    @Test
    void unversionedAliasIsAlsoProtected() throws Exception {
        when(graph.documentGraph("tenant-a", "source-version-1")).thenReturn(
                new DocumentGraphResponse("source-version-1", "source-version-1",
                        List.of(), List.of(), false));

        mvc.perform(get("/documents/source-version-1/graph")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isOk());
        mvc.perform(get("/documents/source-version-1/graph"))
                .andExpect(status().isUnauthorized());
    }

    private static String token(String tenant) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .subject("test")
                        .claim("tenant_id", tenant)
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                        .build());
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
