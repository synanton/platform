package org.synanton.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.synanton.controlplane.app.ControlPlaneProperties;
import org.synanton.controlplane.infra.HttpService;
import org.synanton.controlplane.service.ModelServingDirectory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/admin")
public class AdminTenantController {

    private static final Logger log = LoggerFactory.getLogger(AdminTenantController.class);

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record CreateTenantRequest(
            @NotBlank String tenantId,
            @NotBlank String displayName,
            @NotBlank String ownerEmail
    ) {}

    public record UpdatePolicyRequest(
            int qpsLimit,
            double monthlyUsdLimit,
            int maxLatencyMs
    ) {}

    public record CreateUserRequest(
            @NotBlank String tenantId,
            @NotBlank String username,
            int uid,
            List<Integer> gids
    ) {}

    public record CreateApiKeyRequest(
            @NotBlank String tenantId,
            @NotBlank String label,
            List<String> scopes
    ) {}

    // ── Dependencies ─────────────────────────────────────────────────────────

    private final HttpService httpService;
    private final ModelServingDirectory modelDirectory;
    private final String topologyUrl;
    private final String securityUrl;
    private final ObjectMapper objectMapper;

    public AdminTenantController(HttpService httpService,
                                 ModelServingDirectory modelDirectory,
                                 ControlPlaneProperties props) {
        this.httpService = httpService;
        this.modelDirectory = modelDirectory;
        this.topologyUrl = props.topologyUrl();
        this.securityUrl = props.securityUrl();
        this.objectMapper = httpService.objectMapper();
    }

    // ── Tenant endpoints ─────────────────────────────────────────────────────

    @GetMapping("/tenants")
    public ResponseEntity<?> listTenants(
            @RequestHeader("Authorization") String authHeader) {
        try {
            JsonNode result = httpService.getWithAuthHeader(
                    topologyUrl + "/topology/tenants", authHeader);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to list tenants: {}", e.getMessage());
            return upstreamError(e);
        }
    }

    @PostMapping("/tenants")
    public ResponseEntity<?> createTenant(
            @Valid @RequestBody CreateTenantRequest req,
            @RequestHeader("Authorization") String authHeader) {
        try {
            // Build topology create-tenant request
            ObjectNode body = objectMapper.createObjectNode();
            body.put("tenantId", req.tenantId());
            body.put("displayName", req.displayName());
            body.put("ownerSubjectId", "user:" + req.ownerEmail());

            JsonNode created = httpService.postWithAuthHeader(
                    topologyUrl + "/topology/tenants",
                    objectMapper.writeValueAsString(body),
                    authHeader);

            // Apply default policy
            ObjectNode policy = objectMapper.createObjectNode();
            policy.put("qpsLimit", 10);
            policy.put("monthlyUsdLimit", 10.0);
            policy.put("maxLatencyMs", 30000);

            try {
                httpService.putWithAuthHeader(
                        topologyUrl + "/topology/tenants/" + req.tenantId() + "/policy",
                        objectMapper.writeValueAsString(policy),
                        authHeader);
            } catch (Exception policyEx) {
                log.warn("Default policy could not be applied for tenant {}: {}",
                        req.tenantId(), policyEx.getMessage());
            }

            return ResponseEntity.status(201).body(created);
        } catch (Exception e) {
            log.error("Failed to create tenant: {}", e.getMessage());
            return upstreamError(e);
        }
    }

    @PutMapping("/tenants/{tenantId}/policy")
    public ResponseEntity<?> updatePolicy(
            @PathVariable String tenantId,
            @RequestBody UpdatePolicyRequest req,
            @RequestHeader("Authorization") String authHeader) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("qpsLimit", req.qpsLimit());
            body.put("monthlyUsdLimit", req.monthlyUsdLimit());
            body.put("maxLatencyMs", req.maxLatencyMs());

            httpService.putWithAuthHeader(
                    topologyUrl + "/topology/tenants/" + tenantId + "/policy",
                    objectMapper.writeValueAsString(body),
                    authHeader);

            return ResponseEntity.ok(Map.of("status", "updated", "tenantId", tenantId));
        } catch (Exception e) {
            log.error("Failed to update policy for tenant {}: {}", tenantId, e.getMessage());
            return upstreamError(e);
        }
    }

    // ── User endpoints ────────────────────────────────────────────────────────

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest req) {
        // Phase 3: user insertion is handled by the FS seeder in topology
        return ResponseEntity.status(501).body(
                Map.of("error", "User creation via API not implemented in Phase 3; use filesystem seeder"));
    }

    // ── API Key endpoints ─────────────────────────────────────────────────────

    @PostMapping("/api-keys")
    public ResponseEntity<?> createApiKey(
            @Valid @RequestBody CreateApiKeyRequest req,
            @RequestHeader("Authorization") String authHeader) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("label", req.label());
            if (req.scopes() != null) {
                body.set("scopes", objectMapper.valueToTree(req.scopes()));
            } else {
                body.set("scopes", objectMapper.createArrayNode());
            }

            JsonNode result = httpService.postWithAuthHeader(
                    securityUrl + "/auth/api-keys",
                    objectMapper.writeValueAsString(body),
                    authHeader);

            return ResponseEntity.status(201).body(result);
        } catch (Exception e) {
            log.error("Failed to create API key: {}", e.getMessage());
            return upstreamError(e);
        }
    }

    // ── Model endpoints ───────────────────────────────────────────────────────

    @GetMapping("/models")
    public ResponseEntity<?> listModels() {
        return ResponseEntity.ok(modelDirectory.getAll());
    }

    @GetMapping("/models/{modelId}")
    public ResponseEntity<?> getModel(@PathVariable String modelId) {
        Optional<ControlPlaneProperties.ModelEntry> model = modelDirectory.getById(modelId);
        if (model.isEmpty()) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "Model not found: " + modelId));
        }
        return ResponseEntity.ok(model.get());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<?> upstreamError(Exception e) {
        String message = e.getMessage();
        if (message != null && message.startsWith("Upstream error 404")) {
            return ResponseEntity.status(404).body(Map.of("error", "Resource not found"));
        }
        return ResponseEntity.status(503).body(Map.of("error", "Upstream service error: " + message));
    }
}
