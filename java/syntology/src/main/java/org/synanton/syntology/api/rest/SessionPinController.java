package org.synanton.syntology.api.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.common.tenant.TenantContext;
import org.synanton.syntology.domain.service.SessionPinService;
import org.synanton.syntology.infra.jdbc.SessionPinRepository.SessionPinRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ontology/session-pin")
public class SessionPinController {

    private final SessionPinService sessionPinService;

    public SessionPinController(SessionPinService sessionPinService) {
        this.sessionPinService = sessionPinService;
    }

    /**
     * POST /api/v1/ontology/session-pin
     * Body: { "tenantId": "...", "version": "..." }
     * Creates or updates a session pin with 24h TTL.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPin(@RequestBody PinRequest request) {
        RequestContext ctx = resolveContext(request.tenantId());
        SessionPinRecord pin = sessionPinService.pin(ctx.tenantId(), ctx.subjectId(), request.version(), Duration.ofHours(24));
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(pin));
    }

    /**
     * GET /api/v1/ontology/session-pin
     * Returns the current pin for the caller or 404.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getPin() {
        RequestContext ctx = resolveContext(null);
        return sessionPinService.getPin(ctx.tenantId(), ctx.subjectId())
                .map(pin -> ResponseEntity.ok(toDto(pin)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/v1/ontology/session-pin
     * Deletes the current session pin. Returns 200 if deleted, 404 if not found.
     */
    @DeleteMapping
    public ResponseEntity<Void> deletePin() {
        RequestContext ctx = resolveContext(null);
        boolean deleted = sessionPinService.unpin(ctx.tenantId(), ctx.subjectId());
        return deleted ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // ---- helpers ----

    private Map<String, Object> toDto(SessionPinRecord pin) {
        long remainingSeconds = Math.max(0, Duration.between(Instant.now(), pin.expiresAt()).toSeconds());
        return Map.of(
                "pin_id", pin.pinId().toString(),
                "tenant_id", pin.tenantId(),
                "version", pin.version(),
                "subject_id", pin.subjectId(),
                "pinned_at", pin.pinnedAt().toString(),
                "expires_at", pin.expiresAt().toString(),
                "remaining_seconds", remainingSeconds
        );
    }

    /**
     * Resolves the request context from the thread-local TenantContext (set by filters)
     * or falls back to the tenant ID supplied in the request body / defaults.
     */
    private RequestContext resolveContext(String requestBodyTenantId) {
        TenantContext ctx = TenantContext.get();
        if (ctx != null) {
            String tenantId = ctx.tenantId() != null ? ctx.tenantId() : "demo";
            String subjectId = ctx.subject() != null ? ctx.subject() : "anonymous";
            return new RequestContext(tenantId, subjectId);
        }
        // Fall back: use request-body tenant if provided, else "demo"
        String tenantId = (requestBodyTenantId != null && !requestBodyTenantId.isBlank())
                ? requestBodyTenantId : "demo";
        return new RequestContext(tenantId, "anonymous");
    }

    // ---- inner types ----

    public record PinRequest(String tenantId, String version) {}

    private record RequestContext(String tenantId, String subjectId) {}
}
