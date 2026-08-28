package org.synanton.topology.infra.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.ClassGrantMutationService;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcClassGrantMutationStore implements ClassGrantMutationService.ClassGrantStore,
        ClassGrantMutationService.OutboxStore, ClassGrantMutationService.AuditStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcClassGrantMutationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public UUID insertPending(ClassGrantMutationService.ClassGrantCommand command, Instant now) {
        UUID grantId = UUID.randomUUID();
        UUID subjectUuid = UUID.nameUUIDFromBytes(command.subjectId().getBytes());
        jdbc.update(
                """
                INSERT INTO topology.class_grants
                  (grant_id, org_id, subject_id, subject_type, class, permission, tenant_id,
                   subject_key, propagation_state, created_at)
                VALUES (?, '00000000-0000-0000-0000-000000000001'::uuid, ?, ?, ?, ?, ?,
                        ?, 'PENDING_PROPAGATION', ?)
                """,
                grantId,
                subjectUuid,
                command.subjectType(),
                command.sensitivityClass(),
                command.permission(),
                command.tenantId(),
                command.subjectId(),
                Timestamp.from(now)
        );
        return grantId;
    }

    @Override
    public void markPropagated(UUID grantId, Instant at) {
        jdbc.update(
                """
                UPDATE topology.class_grants
                SET propagation_state = 'PROPAGATED', propagated_at = ?
                WHERE grant_id = ?
                """,
                Timestamp.from(at),
                grantId
        );
    }

    @Override
    public void markStuck(UUID grantId) {
        jdbc.update("UPDATE topology.class_grants SET propagation_state = 'STUCK' WHERE grant_id = ?", grantId);
    }

    @Override
    public void revoke(UUID grantId, Instant at) {
        jdbc.update(
                "UPDATE topology.class_grants SET revoked_at = ? WHERE grant_id = ? AND revoked_at IS NULL",
                Timestamp.from(at),
                grantId
        );
    }

    @Override
    public String currentStateJson(String tenantId, String subjectId, String sensitivityClass) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT grant_id, class, permission, propagation_state FROM topology.class_grants
                WHERE tenant_id = ? AND subject_key = ? AND class = ? AND revoked_at IS NULL
                ORDER BY created_at DESC LIMIT 1
                """,
                tenantId,
                subjectId,
                sensitivityClass
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
