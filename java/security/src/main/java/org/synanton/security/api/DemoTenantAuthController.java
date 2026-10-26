package org.synanton.security.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.security.issuance.DemoTenantTokenIssuer;
import org.synanton.security.issuance.IssuanceAuditException;
import org.synanton.storage.contract.service.ServiceAuthorizationException;

/**
 * Demo-only tenant token mint. Enabled only with
 * {@code security.demo-issuance.enabled=true}; absent by default. Not a
 * production endpoint; #436 owns the real issuer.
 */
@RestController
@ConditionalOnProperty(name = "security.demo-issuance.enabled", havingValue = "true")
public class DemoTenantAuthController {

    private final DemoTenantTokenIssuer issuer;

    public DemoTenantAuthController(DemoTenantTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @PostMapping("/v1/auth/tenant")
    public ResponseEntity<?> issue(@RequestBody IssueRequest request) {
        if (request.principalId() == null || request.principalId().isBlank()
                || request.tenantId() == null || request.tenantId().isBlank()) {
            return ResponseEntity.badRequest().body(new Error(
                    "VALIDATION_ERROR", "principal_id and tenant_id are required"));
        }
        try {
            var issued = issuer.issue(request.principalId(), request.tenantId(),
                    Set.copyOf(request.capabilities() == null ? List.of() : request.capabilities()));
            return ResponseEntity.ok(new IssueResponse(
                    issued.token(), issued.expiresAt().getEpochSecond(),
                    request.tenantId(), issued.principal().capabilities().stream()
                            .map(Enum::name).sorted().toList()));
        } catch (ServiceAuthorizationException rejected) {
            return ResponseEntity.badRequest().body(new Error(
                    "UNKNOWN_CAPABILITY_CLAIM", rejected.getMessage()));
        } catch (IssuanceAuditException auditFailure) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Error(
                    "ISSUANCE_AUDIT_UNAVAILABLE", "token not issued"));
        }
    }

    public record IssueRequest(
            @JsonProperty("principal_id") String principalId,
            @JsonProperty("tenant_id") String tenantId,
            List<String> capabilities) {}

    public record IssueResponse(
            String token,
            @JsonProperty("expires_at") long expiresAt,
            @JsonProperty("tenant_id") String tenantId,
            List<String> capabilities) {}

    public record Error(String code, String detail) {}
}
