package org.synanton.topology.infra.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.GrantMutationService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
    public UUID insertPending(Connection connection, GrantMutationService.GrantCommand command, Instant now) {
        UUID grantId = UUID.randomUUID();
        UUID subjectUuid = UUID.nameUUIDFromBytes(command.subjectId().getBytes());
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO topology.acl_grants
                  (grant_id, org_id, subject_id, subject_type, resource_path, permission, source, tenant_id,
                   propagation_state, resource_id, resource_type, subject_key, created_at)
                VALUES (?, '00000000-0000-0000-0000-000000000001'::uuid, ?, ?, ?, ?, 'MANUAL', ?,
                        'PENDING_PROPAGATION', ?, ?, ?, ?)
                """)) {
            insert.setObject(1, grantId);
            insert.setObject(2, subjectUuid);
            insert.setString(3, command.subjectType());
            insert.setString(4, command.resourceId());
            insert.setString(5, command.permission());
            insert.setString(6, command.tenantId());
            insert.setString(7, command.resourceId());
            insert.setString(8, command.resourceType());
            insert.setString(9, command.subjectId());
            insert.setTimestamp(10, Timestamp.from(now));
            insert.executeUpdate();
            return grantId;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert acl_grants row", e);
        }
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
    public UUID insert(Connection connection, String eventType, Map<String, Object> payload, Instant now) {
        UUID outboxId = UUID.randomUUID();
        try {
            String json = mapper.writeValueAsString(payload);
            try (PreparedStatement insert = connection.prepareStatement(
                    """
                    INSERT INTO topology.topology_outbox (event_id, event_type, payload, created_at, ack_state)
                    VALUES (?, ?, CAST(? AS jsonb), ?, CAST('{}' AS jsonb))
                    """)) {
                insert.setObject(1, outboxId);
                insert.setString(2, eventType);
                insert.setString(3, json);
                insert.setTimestamp(4, Timestamp.from(now));
                insert.executeUpdate();
            }
            return outboxId;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to insert outbox row", e);
        }
    }

    @Override
    public void insert(
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
    ) {
        try {
            String json = mapper.writeValueAsString(payload);
            try (PreparedStatement insert = connection.prepareStatement(
                    """
                    INSERT INTO audit.admin_audit
                      (actor_subject_id, actor_type, actor_role, target_tenant_id, action, target_resource_id,
                       before_state_hash, after_state_hash, payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """)) {
                insert.setString(1, actorSubjectId);
                insert.setString(2, actorType);
                insert.setString(3, actorRole);
                insert.setString(4, tenantId);
                insert.setString(5, action);
                insert.setString(6, resourceId);
                insert.setString(7, beforeHash);
                insert.setString(8, afterHash);
                insert.setString(9, json);
                insert.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to insert admin_audit row", e);
        }
    }
}
