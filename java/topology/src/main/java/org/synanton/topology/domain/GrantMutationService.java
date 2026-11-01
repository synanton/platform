package org.synanton.topology.domain;

import org.synanton.common.grpc.validation.PgvFieldViolation;
import org.synanton.common.grpc.validation.PgvRuleCatalogue;
import org.synanton.topology.domain.event.GrantCreated;
import org.synanton.topology.domain.model.OrganizationPolicy;
import org.synanton.topology.domain.model.PropagationId;
import org.synanton.topology.infra.events.TopologyEventPublisher;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
        UUID insertPending(Connection connection, GrantCommand command, Instant now);
        void markPropagated(UUID grantId, Instant at);
        void markStuck(UUID grantId);
        String currentStateJson(String tenantId, String subjectId, String resourceId);
    }

    public interface AuditStore {
        void insert(
                Connection connection,
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
    private final AuditStore audit;
    private final PolicyLookup policies;
    private final Clock clock;
    private final TopologyEventPublisher events;

    public GrantMutationService(
            PgvRuleCatalogue catalogue,
            GrantStore grants,
            AuditStore audit,
            PolicyLookup policies,
            Clock clock,
            TopologyEventPublisher events
    ) {
        this.catalogue = catalogue;
        this.grants = grants;
        this.audit = audit;
        this.policies = policies;
        this.clock = clock;
        this.events = events;
    }

    public PropagationId grant(GrantCommand command) {
        List<PgvFieldViolation> violations = catalogue.validate("TopologyMutation", "Grant", command);
        if (!violations.isEmpty()) {
            throw new InvalidGrantException(violations);
        }
        OrganizationPolicy policy = policies.require(command.tenantId());
        Instant now = Instant.now(clock);
        String before = grants.currentStateJson(command.tenantId(), command.subjectId(), command.resourceId());
        AtomicReference<UUID> grantId = new AtomicReference<>();
        UUID outboxId = events.publishGrantCreated(
                command.tenantId(),
                new GrantCreated(command.tenantId(), command.subjectId(), command.resourceId(), command.permission()),
                connection -> {
                    UUID id = grants.insertPending(connection, command, now);
                    grantId.set(id);
                    Map<String, Object> afterState = Map.of(
                            "grantId", id.toString(),
                            "tenantId", command.tenantId(),
                            "subjectId", command.subjectId(),
                            "resourceId", command.resourceId(),
                            "permission", command.permission(),
                            "tier", policy.tier()
                    );
                    audit.insert(
                            connection,
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
                });
        return new PropagationId(grantId.get(), outboxId, PropagationId.PENDING, now);
    }

    public static class InvalidGrantException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final transient List<PgvFieldViolation> violations;

        public InvalidGrantException(List<PgvFieldViolation> violations) {
            super("PGV validation failed");
            this.violations = List.copyOf(violations);
        }

        public List<PgvFieldViolation> violations() {
            return violations;
        }
    }
}
