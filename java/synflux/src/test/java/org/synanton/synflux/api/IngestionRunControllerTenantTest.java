package org.synanton.synflux.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionRunRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.synanton.ingestioncache.domain.SyncResultRow;
import org.synanton.synflux.lifecycle.FixtureSourceWorkflow;
import org.synanton.synflux.lifecycle.VerifiedIngestionContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IngestionRunControllerTenantTest {

    private static final String SECRET = "synflux-test-jwt-secret-at-least-32-bytes";

    private FixtureSourceWorkflow workflow;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        workflow = mock(FixtureSourceWorkflow.class);
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "health-probe", "/actuator");
        mvc = MockMvcBuilders.standaloneSetup(new IngestionRunController(workflow, cache)).addFilters(filter).build();
    }

    @Test
    void runSubmissionUsesVerifiedTenantAndPrincipal() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        IngestionRunRow run = new IngestionRunRow("tenant-a", runId, operationId, "fixture", "COMPLETED",
            now, now, new SyncResultRow(0, 1, 0, 0, 0, 0, 0, 0), null);
        OperationRow operation = new OperationRow("tenant-a", operationId, "INGESTION", "SUCCEEDED", "alice",
            now, now, now, 1, 1, "FULL", null, "request-1", null);
        when(workflow.submit(any(VerifiedIngestionContext.class), eq("fixture"), eq("manuals/policy.md"),
            anyString(), eq("key-1"))).thenReturn(new FixtureSourceWorkflow.Submission(run, operation));

        mvc.perform(post("/v1/ingestion-runs")
                .header("Authorization", "Bearer " + token("tenant-a"))
                .header("X-Tenant", "tenant-b")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant_id\":\"tenant-b\",\"source_id\":\"fixture\","
                    + "\"resource_id\":\"manuals/policy.md\"}"))
            .andExpect(status().isAccepted());

        ArgumentCaptor<VerifiedIngestionContext> context = ArgumentCaptor.forClass(VerifiedIngestionContext.class);
        verify(workflow).submit(context.capture(), eq("fixture"), eq("manuals/policy.md"),
            anyString(), eq("key-1"));
        assertThat(context.getValue()).isEqualTo(new VerifiedIngestionContext("tenant-a", "alice", "USER"));
    }

    @Test
    void runSubmissionRequiresVerifiedBearerToken() throws Exception {
        mvc.perform(post("/v1/ingestion-runs")
                .header("X-Tenant", "tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source_id\":\"fixture\",\"resource_id\":\"manuals/policy.md\"}"))
            .andExpect(status().isUnauthorized());

        verify(workflow, never()).submit(any(), anyString(), anyString(), anyString(), anyString());
    }

    private static String token(String tenantId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("alice")
            .claim("tenant_id", tenantId)
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
