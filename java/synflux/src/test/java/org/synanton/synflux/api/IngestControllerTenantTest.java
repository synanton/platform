package org.synanton.synflux.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContext;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.synflux.runner.IngestionJobRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IngestControllerTenantTest {

    private static final String SECRET = "synflux-test-jwt-secret-at-least-32-bytes";

    private IngestionJobRunner runner;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runner = mock(IngestionJobRunner.class);
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "health-probe", "/actuator");
        mvc = MockMvcBuilders.standaloneSetup(new IngestController(runner, cache)).addFilters(filter).build();
    }

    @Test
    void requestBodyAndTenantHeaderCannotOverrideVerifiedTenant() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(runner.startJob("tenant-a", "filesystem", "/fixture/docs")).thenReturn(jobId);

        mvc.perform(post("/ingest/run")
                .header("Authorization", "Bearer " + token("tenant-a"))
                .header("X-Tenant", "tenant-b")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant\":\"tenant-b\",\"source\":\"filesystem\",\"path\":\"/fixture/docs\"}"))
            .andExpect(status().isOk());

        verify(runner).startJob("tenant-a", "filesystem", "/fixture/docs");
        TenantContext.clear();
    }

    @Test
    void missingBearerTokenIsRejectedEvenWhenTenantHeaderIsPresent() throws Exception {
        mvc.perform(post("/ingest/run")
                .header("X-Tenant", "tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"filesystem\",\"path\":\"/fixture/docs\"}"))
            .andExpect(status().isUnauthorized());

        verify(runner, never()).startJob(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void jobLookupUsesVerifiedTenantAndIgnoresTenantQueryParameter() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(runner.getJob("tenant-a", jobId)).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/ingest/jobs/{id}", jobId)
                .header("Authorization", "Bearer " + token("tenant-a"))
                .param("tenant", "tenant-b"))
            .andExpect(status().isNotFound());

        verify(runner).getJob("tenant-a", jobId);
        TenantContext.clear();
    }

    private static String token(String tenantId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("fixture-user")
            .claim("tenant_id", tenantId)
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
