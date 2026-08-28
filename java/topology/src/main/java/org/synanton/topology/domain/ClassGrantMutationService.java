package org.synanton.topology.domain;

import org.synanton.common.grpc.validation.PgvFieldViolation;
import org.synanton.common.grpc.validation.PgvRuleCatalogue;
import org.synanton.topology.domain.model.OrganizationPolicy;
import org.synanton.topology.domain.model.PropagationId;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ClassGrantMutationService {

    public record ClassGrantCommand(
            String tenantId,
            String subjectId,
            String subjectType,
            String sensitivityClass,
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
            map.put("class", sensitivityClass);
            map.put("permission", permission);
            map.put("idempotency_key", idempotencyKey);
            return map;
        }
    }

    public interface ClassGrantStore {
        UUID insertPending(ClassGrantCommand command, Instant now);

        void markPropagated(UUID grantId, Instant at);

        void markStuck(UUID grantId);

        void revoke(UUID grantId, Instant at);

        String currentStateJson(String tenantId, String subjectId, String sensitivityClass);
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
    private final ClassGrantStore grants;
    private final OutboxStore outbox;
    private final AuditStore audit;
    private final PolicyLookup policies;
    private final Clock clock;

    public ClassGrantMutationService(
            PgvRuleCatalogue catalogue,
            ClassGrantStore grants,
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

    public PropagationId grant(ClassGrantCommand command) {
        List<PgvFieldViolation> violations = catalogue.validate("TopologyMutation", "ClassGrant", command);
        if (!violations.isEmpty()) {
            throw new InvalidClassGrantException(violations);
        }
        OrganizationPolicy policy = policies.require(command.tenantId());
        Instant now = Instant.now(clock);
        String before = grants.currentStateJson(command.tenantId(), command.subjectId(), command.sensitivityClass());
        UUID grantId = grants.insertPending(command, now);
        Map<String, Object> afterState = Map.of(
                "grantId", grantId.toString(),
                "tenantId", command.tenantId(),
                "subjectId", command.subjectId(),
                "class", command.sensitivityClass(),
                "permission", command.permission(),
                "tier", policy.tier()
        );
        UUID outboxId = outbox.insert("CLASS_GRANT_UPSERT", Map.of(
                "event_type", "CLASS_GRANT_UPSERT",
                "grant_id", grantId.toString(),
                "tenant_id", command.tenantId(),
                "subject_id", command.subjectId(),
                "class", command.sensitivityClass(),
                "permission", command.permission()
        ), now);
        audit.insert(
                command.actorSubjectId(),
                command.actorType(),
                command.actorRole(),
                command.tenantId(),
                "CLASS_GRANT_CREATED",
                command.sensitivityClass(),
                StateHasher.sha256(before == null ? "" : before),
                StateHasher.sha256(afterState),
                afterState
        );
        return new PropagationId(grantId, outboxId, PropagationId.PENDING, now);
    }

    public PropagationId revoke(
            UUID grantId,
            String tenantId,
            String actorSubjectId,
            String actorType,
            String actorRole
    ) {
        Instant now = Instant.now(clock);
        grants.revoke(grantId, now);
        UUID outboxId = outbox.insert("CLASS_GRANT_REVOKE", Map.of(
                "event_type", "CLASS_GRANT_REVOKE",
                "grant_id", grantId.toString(),
                "tenant_id", tenantId
        ), now);
        audit.insert(
                actorSubjectId,
                actorType,
                actorRole,
                tenantId,
                "CLASS_GRANT_REVOKED",
                grantId.toString(),
                StateHasher.sha256("active"),
                StateHasher.sha256("revoked"),
                Map.of("grantId", grantId.toString())
        );
        return new PropagationId(grantId, outboxId, PropagationId.PENDING, now);
    }

    public static class InvalidClassGrantException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final transient List<PgvFieldViolation> violations;

        public InvalidClassGrantException(List<PgvFieldViolation> violations) {
            super("PGV validation failed");
            this.violations = List.copyOf(violations);
        }

        public List<PgvFieldViolation> violations() {
            return violations;
        }
    }
}
