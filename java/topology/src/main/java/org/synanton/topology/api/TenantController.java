package org.synanton.topology.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.synanton.common.error.NotFoundException;
import org.synanton.topology.domain.model.Connector;
import org.synanton.topology.domain.model.Grant;
import org.synanton.topology.domain.model.Tenant;
import org.synanton.topology.domain.model.TenantPolicy;
import org.synanton.topology.infra.jdbc.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/topology")
public class TenantController {

    // ── DTOs ────────────────────────────────────────────────────────────────

    public record CreateTenantRequest(String tenantId, String displayName, String ownerSubjectId) {}

    public record TenantResponse(String tenantId, String displayName, Instant createdAt) {}

    public record CreateGrantRequest(String subjectId, String subjectType, String resourcePath, String permission) {}

    public record GrantResponse(UUID grantId, String tenantId, String subjectId,
                                String resourcePath, String permission, Instant createdAt) {}

    public record PolicyRequest(int qpsLimit, BigDecimal monthlyUsdLimit, int maxLatencyMs) {}

    public record PolicyResponse(String tenantId, int qpsLimit, BigDecimal monthlyUsdLimit, int maxLatencyMs) {}

    // ── Dependencies ────────────────────────────────────────────────────────

    private final JdbcTenantRepository tenants;
    private final JdbcGrantRepository grants;
    private final JdbcPolicyRepository policies;
    private final JdbcOutboxRepository outbox;
    private final JdbcConnectorRepository connectors;
    private final ObjectMapper objectMapper;

    public TenantController(JdbcTenantRepository tenants,
                            JdbcGrantRepository grants,
                            JdbcPolicyRepository policies,
                            JdbcOutboxRepository outbox,
                            JdbcConnectorRepository connectors,
                            ObjectMapper objectMapper) {
        this.tenants = tenants;
        this.grants = grants;
        this.policies = policies;
        this.outbox = outbox;
        this.connectors = connectors;
        this.objectMapper = objectMapper;
    }

    // ── Tenant endpoints ────────────────────────────────────────────────────

    @GetMapping("/tenants")
    public List<TenantResponse> listTenants() {
        return tenants.findAll().stream()
                .map(t -> new TenantResponse(t.tenantId(), t.displayName(), t.createdAt()))
                .toList();
    }

    @PostMapping("/tenants")
    public ResponseEntity<TenantResponse> createTenant(@RequestBody CreateTenantRequest req) {
        Instant now = Instant.now();
        Tenant tenant = new Tenant(req.tenantId(), req.displayName(), now);
        tenants.insert(tenant);

        String payload = toJson(Map.of(
                "tenantId", req.tenantId(),
                "displayName", req.displayName(),
                "ownerSubjectId", req.ownerSubjectId() != null ? req.ownerSubjectId() : ""
        ));
        outbox.insert("TENANT_CREATED", payload);

        return ResponseEntity.status(201)
                .body(new TenantResponse(tenant.tenantId(), tenant.displayName(), tenant.createdAt()));
    }

    @GetMapping("/tenants/{tenantId}")
    public TenantResponse getTenant(@PathVariable String tenantId) {
        Tenant t = tenants.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("No tenant with id=" + tenantId));
        return new TenantResponse(t.tenantId(), t.displayName(), t.createdAt());
    }

    // ── Policy endpoints ────────────────────────────────────────────────────

    @GetMapping("/tenants/{tenantId}/policy")
    public PolicyResponse getPolicy(@PathVariable String tenantId) {
        TenantPolicy p = policies.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("No policy for tenant=" + tenantId));
        return new PolicyResponse(p.tenantId(), p.qpsLimit(), p.monthlyUsdLimit(), p.maxLatencyMs());
    }

    @PutMapping("/tenants/{tenantId}/policy")
    public PolicyResponse upsertPolicy(@PathVariable String tenantId, @RequestBody PolicyRequest req) {
        TenantPolicy policy = new TenantPolicy(tenantId, req.qpsLimit(), req.monthlyUsdLimit(), req.maxLatencyMs());
        policies.upsert(policy);
        return new PolicyResponse(tenantId, policy.qpsLimit(), policy.monthlyUsdLimit(), policy.maxLatencyMs());
    }

    // ── Grant endpoints ─────────────────────────────────────────────────────

    @PostMapping("/grants")
    public ResponseEntity<GrantResponse> createGrant(@RequestParam String tenantId,
                                                     @RequestBody CreateGrantRequest req) {
        Grant grant = new Grant(
                null,
                tenantId,
                req.subjectId(),
                req.subjectType(),
                req.resourcePath(),
                req.permission(),
                "MANUAL",
                Instant.now()
        );
        Grant saved = grants.insert(grant);

        String payload = toJson(Map.of(
                "tenantId", tenantId,
                "subjectId", req.subjectId(),
                "subjectType", req.subjectType(),
                "resourcePath", req.resourcePath(),
                "permission", req.permission()
        ));
        outbox.insert("GRANT_CREATED", payload);

        return ResponseEntity.status(201).body(new GrantResponse(
                saved.grantId(),
                saved.tenantId(),
                saved.subjectId(),
                saved.resourcePath(),
                saved.permission(),
                saved.createdAt()
        ));
    }

    @DeleteMapping("/grants/{grantId}")
    public ResponseEntity<Map<String, Object>> revokeGrant(@PathVariable UUID grantId) {
        Instant now = Instant.now();
        boolean revoked = grants.revoke(grantId, now);
        if (!revoked) {
            throw new NotFoundException("No active grant with id=" + grantId);
        }

        String payload = toJson(Map.of(
                "grantId", grantId.toString(),
                "revokedAt", now.toString()
        ));
        outbox.insert("GRANT_REVOKED", payload);

        return ResponseEntity.ok(Map.of("status", "revoked", "grantId", grantId));
    }

    // ── Connector endpoints ─────────────────────────────────────────────────

    @GetMapping("/connectors")
    public List<Connector> listConnectors() {
        return connectors.findAll();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox payload", e);
        }
    }
}
