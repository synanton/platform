package org.synanton.topology.infra.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.GrantMutationService;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcGrantMutationStore implements GrantMutationService.GrantStore, GrantMutationService.OutboxStore,
        GrantMutationService.AuditStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcGrantMutationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public UUID insertPending(GrantMutationService.GrantCommand command, Instant now) {
        UUID grantId = UUID.randomUUID();
        UUID subjectUuid = UUID.nameUUIDFromBytes(command.subjectId().getBytes());
        jdbc.update(
                """
                INSERT INTO topology.acl_grants
                  (grant_id, org_id, subject_id, subject_type, resource_path, permission, source, tenant_id,
                   propagation_state, resource_id, resource_type, subject_key, created_at)
                VALUES (?, '00000000-0000-0000-0000-000000000001'::uuid, ?, ?, ?, ?, 'MANUAL', ?,
                        'PENDING_PROPAGATION', ?, ?, ?, ?)
                """,
                grantId,
                subjectUuid,
                command.subjectType(),
                command.resourceId(),
                command.permission(),
                command.tenantId(),
                command.resourceId(),
                command.resourceType(),
                command.subjectId(),
                Timestamp.from(now)
        );
        return grantId;
    }

    @Override
    public void markPropagated(UUID grantId, Instant at) {
        jdbc.update(
                "UPDATE topology.acl_grants SET propagation_state = 'PROPAGATED', propagated_at = ? WHERE grant_id = ?",
                Timestamp.from(at),
                grantId
        );
    }

    @Override
    public void markStuck(UUID grantId) {
        jdbc.update("UPDATE topology.acl_grants SET propagation_state = 'STUCK' WHERE grant_id = ?", grantId);
    }

    @Override
    public String currentStateJson(String tenantId, String subjectId, String resourceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT grant_id, permission, propagation_state FROM topology.acl_grants
                WHERE tenant_id = ? AND subject_key = ? AND resource_id = ? AND revoked_at IS NULL
                ORDER BY created_at DESC LIMIT 1
                """,
                tenantId,
                subjectId,
                resourceId
        );
        if (rows.isEmpty()) {
            return "";
        }
        try {
            return mapper.writeValueAsString(rows.getFirst());
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public UUID insert(String eventType, Map<String, Object> payload, Instant now) {
        UUID outboxId = UUID.randomUUID();
        try {
            String json = mapper.writeValueAsString(payload);
            jdbc.update(
                    """
                    INSERT INTO topology.topology_outbox (event_id, event_type, payload, created_at, ack_state)
                    VALUES (?, ?, CAST(? AS jsonb), ?, CAST('{}' AS jsonb))
                    """,
                    outboxId,
                    eventType,
                    json,
                    Timestamp.from(now)
            );
            return outboxId;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to insert outbox row", e);
        }
    }

    @Override
    public void insert(
            String actorSubjectId,
            String actorType,
            String actorRole,
            String tenantId,
            String action,
            String resourceId,
            String beforeHash,
            String afterHash,
            Map<String, Object> payload
    ) {
        try {
            String json = mapper.writeValueAsString(payload);
            jdbc.update(
                    """
                    INSERT INTO audit.admin_audit
                      (actor_subject_id, actor_type, actor_role, target_tenant_id, action, target_resource_id,
                       before_state_hash, after_state_hash, payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """,
                    actorSubjectId,
                    actorType,
                    actorRole,
                    tenantId,
                    action,
                    resourceId,
                    beforeHash,
                    afterHash,
                    json
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to insert admin_audit row", e);
        }
    }
}
