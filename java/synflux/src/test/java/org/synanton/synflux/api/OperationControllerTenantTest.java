package org.synanton.synflux.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.IngestionErrorRow;
import org.synanton.ingestioncache.domain.OperationRow;
import org.synanton.synflux.lifecycle.FixtureSourceWorkflow;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperationControllerTenantTest {

    private static final String SECRET = "synflux-test-jwt-secret-at-least-32-bytes";

    private IngestionCacheClient cache;
    private FixtureSourceWorkflow workflow;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        cache = mock(IngestionCacheClient.class);
        workflow = mock(FixtureSourceWorkflow.class);
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "health-probe", "/actuator");
        mvc = MockMvcBuilders.standaloneSetup(new OperationController(cache, workflow, new ObjectMapper()))
            .addFilters(filter).build();
    }

    @Test
    void operationLookupIsTenantScoped() throws Exception {
        UUID operationId = UUID.randomUUID();
        OperationRow operation = new OperationRow(
            "tenant-a", operationId, "INGESTION", "SUCCEEDED", "alice",
            Instant.parse("2026-10-17T12:00:00Z"), Instant.parse("2026-10-17T12:00:01Z"),
            Instant.parse("2026-10-17T12:00:02Z"), 1, 1, "FULL", null, "req-1", null
        );
        when(cache.readOperation("tenant-a", operationId)).thenReturn(Optional.of(operation));

        mvc.perform(get("/v1/operations/{operationId}", operationId)
                .header("Authorization", "Bearer " + token("tenant-a"))
                .header("X-Tenant", "tenant-b"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.operation_id").value(operationId.toString()))
            .andExpect(jsonPath("$.requested_by.principal_id").value("alice"))
            .andExpect(jsonPath("$.tenant_id").value("tenant-a"))
            .andExpect(jsonPath("$.progress.completed").value(1))
            .andExpect(jsonPath("$.operationId").doesNotExist());

        verify(cache).readOperation("tenant-a", operationId);
        verify(cache, never()).readOperation("tenant-b", operationId);
    }

    @Test
    void cancellationUsesVerifiedTenantAndTransitionsRunningOperation() throws Exception {
        UUID operationId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-17T12:00:00Z");
        OperationRow running = new OperationRow(
            "tenant-a", operationId, "INGESTION", "RUNNING", "alice", createdAt, createdAt,
            null, 0, 1, null, null, "request-1", null);
        OperationRow cancelRequested = new OperationRow(
            "tenant-a", operationId, "INGESTION", "CANCEL_REQUESTED", "alice", createdAt, createdAt,
            null, 0, 1, null, null, "request-1", null);
        when(cache.readOperation("tenant-a", operationId)).thenReturn(Optional.of(running));
        when(workflow.cancelOperation(any(), eq(operationId))).thenReturn(cancelRequested);

        mvc.perform(post("/v1/operations/{operationId}/cancel", operationId)
                .header("Authorization", "Bearer " + token("tenant-a"))
                .header("X-Tenant", "tenant-b"))
            .andExpect(status().isOk());

        var context = org.mockito.ArgumentCaptor.forClass(org.synanton.synflux.lifecycle.VerifiedIngestionContext.class);
        verify(workflow).cancelOperation(context.capture(), eq(operationId));
        assertThat(context.getValue().tenantId()).isEqualTo("tenant-a");
    }

    @Test
    void operationErrorPreservesStructuredDetailsAndSafelyHandlesMalformedJson() throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID errorId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-17T12:00:00Z");
        OperationRow failed = new OperationRow(
            "tenant-a", operationId, "INGESTION", "FAILED", "alice", createdAt, createdAt, createdAt,
            2, 3, null, errorId, "request-1", null);
        IngestionErrorRow structuredError = new IngestionErrorRow(
            "tenant-a", errorId, "about:blank", "Operation failed", 500, "Some resources failed",
            "urn:operation:" + operationId, "OPERATION_FAILED", false, "request-1",
            "{\"reason\":\"QUARANTINED\",\"quarantined\":3}", createdAt);
        when(cache.readOperation("tenant-a", operationId)).thenReturn(Optional.of(failed));
        when(cache.readIngestionError("tenant-a", errorId)).thenReturn(Optional.of(structuredError));

        mvc.perform(get("/v1/operations/{operationId}", operationId)
                .header("Authorization", "Bearer " + token("tenant-a")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error.details.reason").value("QUARANTINED"))
            .andExpect(jsonPath("$.error.details.quarantined").value(3));

        IngestionErrorRow malformedError = new IngestionErrorRow(
            "tenant-a", errorId, "about:blank", "Operation failed", 500, "Some resources failed",
            "urn:operation:" + operationId, "OPERATION_FAILED", false, "request-1", "not-json", createdAt);
        when(cache.readIngestionError("tenant-a", errorId)).thenReturn(Optional.of(malformedError));
        mvc.perform(get("/v1/operations/{operationId}", operationId)
                .header("Authorization", "Bearer " + token("tenant-a")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.error.details.quarantined").doesNotExist());
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
