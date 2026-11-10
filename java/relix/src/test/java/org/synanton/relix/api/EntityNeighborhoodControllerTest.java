package org.synanton.relix.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.relix.graph.EntityNeighborhood;
import org.synanton.relix.graph.GraphConnector;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EntityNeighborhoodControllerTest {

    private static final String SECRET = "relix-test-jwt-secret-at-least-32-bytes";
    private GraphConnector graph;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        graph = mock(GraphConnector.class);
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "", "/health");
        mvc = MockMvcBuilders.standaloneSetup(new EntityNeighborhoodController(graph))
                .addFilters(filter)
                .build();
    }

    @Test
    void returnsBoundedNeighborhoodAndEvidence() throws Exception {
        when(graph.neighborhood("tenant-a", "entity-1", 50)).thenReturn(Optional.of(
                new EntityNeighborhood("entity-1", "Halcyon-7", "MaintenanceSystem",
                        List.of(new EntityNeighborhood.Neighbor("entity-2", "Protocol-A", "Protocol",
                                "requires", "OUTGOING")),
                        List.of(new EntityNeighborhood.Evidence("requires", "entity-2",
                                "chunk-1", "version-1", 4, 12)),
                        false)));

        mvc.perform(get("/v1/entities/entity-1/neighbors")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity.id").value("entity-1"))
                .andExpect(jsonPath("$.neighbors[0].entity.id").value("entity-2"))
                .andExpect(jsonPath("$.neighbors[0].relation").value("requires"))
                .andExpect(jsonPath("$.neighbors[0].direction").value("OUTGOING"))
                .andExpect(jsonPath("$.evidence[0].chunk_id").value("chunk-1"))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void clientLimitCannotExpandTheServerBound() throws Exception {
        when(graph.neighborhood("tenant-a", "entity-1", 50)).thenReturn(Optional.of(
                new EntityNeighborhood("entity-1", "Halcyon-7", "MaintenanceSystem",
                        List.of(), List.of(), true)));

        mvc.perform(get("/v1/entities/entity-1/neighbors")
                        .param("limit", "500")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true));

        verify(graph).neighborhood("tenant-a", "entity-1", 50);
    }

    @Test
    void unknownEntityIs404() throws Exception {
        when(graph.neighborhood("tenant-a", "missing", 50)).thenReturn(Optional.empty());
        mvc.perform(get("/v1/entities/missing/neighbors")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isNotFound());
    }

    @Test
    void clientTenantHeaderAndQueryCannotOverrideTheVerifiedTenant() throws Exception {
        when(graph.neighborhood("tenant-a", "entity-1", 50)).thenReturn(Optional.of(
                new EntityNeighborhood("entity-1", "Halcyon-7", "MaintenanceSystem",
                        List.of(), List.of(), false)));

        mvc.perform(get("/v1/entities/entity-1/neighbors")
                        .param("tenant", "tenant-b")
                        .header("X-Tenant-ID", "tenant-b")
                        .header("Authorization", "Bearer " + token("tenant-a")))
                .andExpect(status().isOk());

        verify(graph).neighborhood("tenant-a", "entity-1", 50);
    }

    @Test
    void missingTokenIsRejected() throws Exception {
        mvc.perform(get("/v1/entities/entity-1/neighbors"))
                .andExpect(status().isUnauthorized());
    }

    private static String token(String tenantId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("ontology-ui")
                .claim("tenant_id", tenantId)
                .jwtID(UUID.randomUUID().toString())
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
