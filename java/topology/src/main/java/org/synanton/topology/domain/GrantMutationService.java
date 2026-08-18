package org.synanton.topology.domain;

import org.synanton.common.grpc.validation.PgvFieldViolation;
import org.synanton.common.grpc.validation.PgvRuleCatalogue;
import org.synanton.topology.domain.model.OrganizationPolicy;
import org.synanton.topology.domain.model.PropagationId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GrantMutationService {

    public record GrantCommand(
            String tenantId,
            String subjectId,
            String subjectType,
            String resourceId,
            String resourceType,
            String permission,
            String idempotencyKey,
            String actorSubjectId,
            String actorType,
            String actorRole
    ) {
        public Map<String, String> asValidationMap() {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("tenant_id", tenantId);
            map.put("subject_id", subjectId);
            map.put("subject_type", subjectType);
            map.put("resource_id", resourceId);
            map.put("resource_type", resourceType);
            map.put("permission", permission);
            map.put("idempotency_key", idempotencyKey);
            return map;
        }
    }

    public interface GrantStore {
        UUID insertPending(GrantCommand command, Instant now);
        void markPropagated(UUID grantId, Instant at);
        void markStuck(UUID grantId);
        String currentStateJson(String tenantId, String subjectId, String resourceId);
    }

    public interface OutboxStore {
        UUID insert(String eventType, Map<String, Object> payload, Instant now);
    }

    public interface AuditStore {
        void insert(
                String actorSubjectId,
                String actorType,
                String actorRole,
                String tenantId,
                String action,
                String resourceId,
                String beforeHash,
                String afterHash,
                Map<String, Object> payload
        );
    }

    public interface PolicyLookup {
        OrganizationPolicy require(String tenantId);
    }

    private final PgvRuleCatalogue catalogue;
    private final GrantStore grants;
    private final OutboxStore outbox;
    private final AuditStore audit;
    private final PolicyLookup policies;
    private final Clock clock;

    public GrantMutationService(
            PgvRuleCatalogue catalogue,
            GrantStore grants,
            OutboxStore outbox,
            AuditStore audit,
            PolicyLookup policies,
            Clock clock
    ) {
        this.catalogue = catalogue;
        this.grants = grants;
        this.outbox = outbox;
        this.audit = audit;
        this.policies = policies;
        this.clock = clock;
    }

    public PropagationId grant(GrantCommand command) {
        List<PgvFieldViolation> violations = catalogue.validate("TopologyMutation", "Grant", command);
        if (!violations.isEmpty()) {
            throw new InvalidGrantException(violations);
        }
        OrganizationPolicy policy = policies.require(command.tenantId());
        Instant now = Instant.now(clock);
        String before = grants.currentStateJson(command.tenantId(), command.subjectId(), command.resourceId());
        UUID grantId = grants.insertPending(command, now);
        Map<String, Object> afterState = Map.of(
                "grantId", grantId.toString(),
                "tenantId", command.tenantId(),
                "subjectId", command.subjectId(),
                "resourceId", command.resourceId(),
                "permission", command.permission(),
                "tier", policy.tier()
        );
        UUID outboxId = outbox.insert("GRANT_CREATED", Map.of(
                "event_type", "GRANT_CREATED",
                "grant_id", grantId.toString(),
                "tenant_id", command.tenantId(),
                "subject_id", command.subjectId(),
                "resource_id", command.resourceId(),
                "permission", command.permission()
        ), now);
        audit.insert(
                command.actorSubjectId(),
                command.actorType(),
                command.actorRole(),
                command.tenantId(),
                "GRANT_CREATED",
                command.resourceId(),
                StateHasher.sha256(before == null ? "" : before),
                StateHasher.sha256(afterState),
                afterState
        );
        return new PropagationId(grantId, outboxId, PropagationId.PENDING, now);
    }

    public static class InvalidGrantException extends RuntimeException {
        private final List<PgvFieldViolation> violations;

        public InvalidGrantException(List<PgvFieldViolation> violations) {
            super("PGV validation failed");
            this.violations = List.copyOf(violations);
        }

        public List<PgvFieldViolation> violations() {
            return violations;
        }
    }
}
