package org.synanton.topology.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.model.Grant;

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

    public Grant insert(Grant grant) {
        jdbc.update(
                """
                INSERT INTO topology.acl_grants
                  (org_id, subject_id, subject_type, resource_path, permission, source, tenant_id)
                VALUES ('00000000-0000-0000-0000-000000000001'::uuid,
                        ?::uuid, ?, ?, ?, ?, ?)
                """,
                grant.subjectId(),
                grant.subjectType(),
                grant.resourcePath(),
                grant.permission(),
                grant.source(),
                grant.tenantId()
        );
        // Re-fetch to get the generated grant_id and created_at
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

    public boolean revoke(UUID grantId, Instant revokedAt) {
        int updated = jdbc.update(
                "UPDATE topology.acl_grants SET revoked_at = ? WHERE grant_id = ? AND revoked_at IS NULL",
                java.sql.Timestamp.from(revokedAt),
                grantId
        );
        return updated > 0;
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
