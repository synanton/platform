package org.synanton.topology.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.common.tenant.TenantContext;
import org.synanton.topology.app.TopologyProperties;
import org.synanton.topology.domain.AckTracker;
import org.synanton.topology.domain.GrantMutationService;
import org.synanton.topology.domain.GrantMutationService.GrantCommand;
import org.synanton.topology.domain.GrantMutationService.InvalidGrantException;
import org.synanton.topology.domain.ResidencyPolicyValidator;
import org.synanton.topology.domain.model.OrganizationPolicy;
import org.synanton.topology.domain.model.PropagationId;
import org.synanton.topology.infra.jdbc.JdbcOrganizationPolicyRepository;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/topology")
public class TopologyMutationController {

    private static final List<String> CONSUMERS = List.of("synquest", "gateway", "relix");

    private final GrantMutationService grants;
    private final AckTracker ackTracker;
    private final JdbcOrganizationPolicyRepository policies;
    private final ResidencyPolicyValidator residencyValidator;
    private final TopologyProperties properties;

    public TopologyMutationController(
            GrantMutationService grants,
            AckTracker ackTracker,
            JdbcOrganizationPolicyRepository policies,
            ResidencyPolicyValidator residencyValidator,
            TopologyProperties properties
    ) {
        this.grants = grants;
        this.ackTracker = ackTracker;
        this.policies = policies;
        this.residencyValidator = residencyValidator;
        this.properties = properties;
    }

    @PostMapping("/mutations/grant")
    public ResponseEntity<PropagationId> grant(@RequestBody GrantCommand body) {
        TenantContext ctx = TenantContext.get();
        GrantCommand command = new GrantCommand(
                body.tenantId(),
                body.subjectId(),
                body.subjectType(),
                body.resourceId(),
                body.resourceType(),
                body.permission(),
                body.idempotencyKey(),
                ctx != null && ctx.subject() != null ? ctx.subject() : "anonymous",
                ctx != null && ctx.hasRole("support_admin") ? "SUPPORT_ADMIN" : "USER_SUBJECT",
                ctx != null && ctx.hasRole("support_admin") ? "support_admin" : "user"
        );
        PropagationId result = grants.grant(command);
        OrganizationPolicy policy = policies.require(body.tenantId());
        if (policy.isHighSecurity()) {
            boolean acked = ackTracker.await(
                    result.outboxId(),
                    CONSUMERS,
                    Duration.ofMillis(properties.highSecurity().ackDeadlineMs())
            );
            if (!acked) {
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .header("Warning", "propagation-pending")
                        .header("Retry-After", "1")
                        .body(new PropagationId(result.grantId(), result.outboxId(), PropagationId.PENDING, result.createdAt()));
            }
            return ResponseEntity.ok(new PropagationId(
                    result.grantId(), result.outboxId(), PropagationId.PROPAGATED, result.createdAt()));
        }
        return ResponseEntity.accepted().body(result);
    }

    @PostMapping("/mutations/acks")
    public Map<String, String> ack(@RequestBody AckRequest ack) {
        ackTracker.record(ack.outboxId(), ack.consumer(), ack.status());
        return Map.of("status", "ok");
    }

    @PostMapping("/tenants/{tenantId}/residency")
    public ResponseEntity<?> upsertResidency(@RequestBody ResidencyUpdate update) {
        residencyValidator.validate(update.allowedRegions(), update.force(), update.contentInDroppedRegion());
        policies.updateAllowedRegions(update.tenantId(), update.allowedRegions());
        if (update.contentInDroppedRegion() && update.force()) {
            return ResponseEntity.ok(Map.of("event", "RESIDENCY_DOWNGRADE_WITH_CONTENT"));
        }
        return ResponseEntity.ok(Map.of("event", "RESIDENCY_UPDATED"));
    }

    @ExceptionHandler(InvalidGrantException.class)
    public ResponseEntity<Map<String, Object>> invalid(InvalidGrantException ex) {
        List<Map<String, String>> fieldViolations = ex.violations().stream()
                .map(v -> Map.of("field", v.field(), "error", v.error(), "description", v.message()))
                .toList();
        return ResponseEntity.badRequest().body(Map.of(
                "grpc_status", "INVALID_ARGUMENT",
                "field_violations", fieldViolations
        ));
    }

    @ExceptionHandler(ResidencyPolicyValidator.ResidencyDowngradeException.class)
    public ResponseEntity<Map<String, String>> downgrade(ResidencyPolicyValidator.ResidencyDowngradeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    public record AckRequest(java.util.UUID outboxId, String consumer, int status) {}

    public record ResidencyUpdate(
            String tenantId,
            List<String> allowedRegions,
            boolean force,
            boolean contentInDroppedRegion
    ) {}
}
