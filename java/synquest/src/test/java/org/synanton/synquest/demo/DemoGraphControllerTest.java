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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoGraphControllerTest {

    private static final String SECRET = "synquest-test-jwt-secret-at-least-32-bytes";
    private static final String CHUNK = "0110d461-0321-5085-874a-ec716a610909#0";
    @TempDir Path temp;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Path graph = temp.resolve("graph.json");
        Files.writeString(graph, "{\"known_chunks\":[\"" + CHUNK + "\",\"empty-chunk\"],\"chunks\":{\"" + CHUNK + "\":{\"neighbor_count\":1,"
                + "\"entities\":[{\"canonical_form\":\"2026\",\"type\":\"date\",\"entity_kind\":\"year\",\"df\":5}],"
                + "\"neighbor_chunks\":[\"03219438-50b5-58c7-8aea-44378f987de8#0\"],"
                + "\"neighbors\":[{\"chunk_id\":\"03219438-50b5-58c7-8aea-44378f987de8#0\","
                + "\"via\":[{\"canonical_form\":\"2026\",\"type\":\"date\",\"entity_kind\":\"year\",\"df\":5}]}],"
                + "\"by_kind\":{\"year\":1}}}}");
        mvc = MockMvcBuilders.standaloneSetup(new DemoGraphController(new DemoGraphService(graph)))
                .addFilters(new TenantContextFilter(new JwtVerifier(SECRET), ""))
                .build();
    }

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(get("/v1/demo/graph/neighbors/{chunkId}", CHUNK)).andExpect(status().isUnauthorized());
    }

    @Test
    void knownChunkReturnsPinnedShape() throws Exception {
        mvc.perform(get("/v1/demo/graph/neighbors/{chunkId}", CHUNK)
                        .header("Authorization", "Bearer " + token("demo-v2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunk_id").value(CHUNK))
                .andExpect(jsonPath("$.neighbor_count").value(1))
                .andExpect(jsonPath("$.by_kind.year").value(1))
                .andExpect(jsonPath("$.neighbors[0].chunk_id").value("03219438-50b5-58c7-8aea-44378f987de8#0"))
                .andExpect(jsonPath("$.neighbors[0].via[0].entity_kind").value("year"));
    }

    @Test
    void knownChunkWithoutGraphReturnsEmpty200() throws Exception {
        mvc.perform(get("/v1/demo/graph/neighbors/{chunkId}", "empty-chunk")
                        .header("Authorization", "Bearer " + token("demo-v2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.neighbor_count").value(0))
                .andExpect(jsonPath("$.entities").isEmpty())
                .andExpect(jsonPath("$.neighbor_chunks").isEmpty());
    }

    @Test
    void unknownChunkIs404() throws Exception {
        mvc.perform(get("/v1/demo/graph/neighbors/unknown")
                        .header("Authorization", "Bearer " + token("demo-v2")))
                .andExpect(status().isNotFound());
    }

    private static String token(String tenantId) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("alice").claim("tenant_id", tenantId)
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
