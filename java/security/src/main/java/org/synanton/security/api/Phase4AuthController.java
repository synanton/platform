package org.synanton.security.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.security.idp.IdpStatusAmortizationCache;
import org.synanton.security.service.SupportAdminService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class Phase4AuthController {

    private final IdpStatusAmortizationCache amortizationCache;
    private final SupportAdminService supportAdminService;

    public Phase4AuthController(
            IdpStatusAmortizationCache amortizationCache,
            SupportAdminService supportAdminService
    ) {
        this.amortizationCache = amortizationCache;
        this.supportAdminService = supportAdminService;
    }

    @PostMapping("/scim/events")
    public ResponseEntity<Void> scimEvent(
            @RequestHeader(value = "X-Scim-Signature", required = false) String signature,
            @RequestBody ScimEvent event
    ) {
        String secret = System.getenv().getOrDefault("KEYCLOAK_SCIM_SECRET", "dev-scim-secret");
        if (signature != null && !hmac(secret, event.subjectId()).equals(signature)) {
            return ResponseEntity.status(401).build();
        }
        amortizationCache.evict(event.subjectId());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/roles/support-admin")
    public Map<String, String> grantSupportAdmin(@RequestBody GrantRoleRequest request) {
        UUID id = supportAdminService.grantRole(
                request.subjectId(), request.identityProfile(), request.source(), request.ttlHours());
        return Map.of("assignment_id", id.toString());
    }

    @PostMapping("/worker/assertion")
    public Map<String, Object> issueWorkerAssertion(@RequestBody WorkerAssertionRequest request) {
        return Map.of(
                "assertion_id", UUID.randomUUID().toString(),
                "parent_assertion_id", request.parentAssertionId(),
                "tenant_id", request.tenantId(),
                "job_id", request.jobId(),
                "expires_at", Instant.now().plusSeconds(3600).toString()
        );
    }

    private static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record ScimEvent(String eventType, String subjectId) {}

    public record GrantRoleRequest(String subjectId, String identityProfile, String source, Integer ttlHours) {}

    public record WorkerAssertionRequest(String jobId, String parentAssertionId, String tenantId) {}
}
