package org.synanton.topology.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.model.Grant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcGrantRepository {

    private final JdbcTemplate jdbc;

    public JdbcGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Grant insert(Connection connection, Grant grant) {
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO topology.acl_grants
                  (org_id, subject_id, subject_type, resource_path, permission, source, tenant_id)
                VALUES ('00000000-0000-0000-0000-000000000001'::uuid,
                        ?::uuid, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, grant.subjectId());
            insert.setString(2, grant.subjectType());
            insert.setString(3, grant.resourcePath());
            insert.setString(4, grant.permission());
            insert.setString(5, grant.source());
            insert.setString(6, grant.tenantId());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert acl_grants row", e);
        }
        List<Grant> results = jdbc.query(
                """
                SELECT grant_id, tenant_id, subject_id, subject_type,
                       resource_path, permission, source, created_at
                FROM topology.acl_grants
                WHERE subject_id = ?::uuid
                  AND resource_path = ?
                  AND permission = ?
                  AND tenant_id = ?
                  AND revoked_at IS NULL
                ORDER BY created_at DESC
                LIMIT 1
                """,
                grantMapper(),
                grant.subjectId(),
                grant.resourcePath(),
                grant.permission(),
                grant.tenantId()
        );
        return results.isEmpty() ? grant : results.get(0);
    }

    public Optional<Grant> findById(UUID grantId) {
        List<Grant> results = jdbc.query(
                """
                SELECT grant_id, tenant_id, subject_id, subject_type,
                       resource_path, permission, source, created_at
                FROM topology.acl_grants
                WHERE grant_id = ?
                """,
                grantMapper(),
                grantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public boolean revoke(Connection connection, UUID grantId, Instant revokedAt) {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE topology.acl_grants SET revoked_at = ? WHERE grant_id = ? AND revoked_at IS NULL")) {
            update.setTimestamp(1, java.sql.Timestamp.from(revokedAt));
            update.setObject(2, grantId);
            int updated = update.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to revoke acl_grants row", e);
        }
    }

    public List<Grant> findByTenantId(String tenantId) {
        return jdbc.query(
                """
                SELECT grant_id, tenant_id, subject_id, subject_type,
                       resource_path, permission, source, created_at
                FROM topology.acl_grants
                WHERE tenant_id = ?
                  AND revoked_at IS NULL
                ORDER BY resource_path
                """,
                grantMapper(),
                tenantId
        );
    }

    private RowMapper<Grant> grantMapper() {
        return (rs, row) -> new Grant(
                UUID.fromString(rs.getString("grant_id")),
                rs.getString("tenant_id"),
                rs.getString("subject_id"),
                rs.getString("subject_type"),
                rs.getString("resource_path"),
                rs.getString("permission"),
                rs.getString("source"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
