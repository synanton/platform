package org.synanton.topology.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.model.AclGrant;
import org.synanton.topology.domain.repository.AclGrantRepository;

import java.util.List;
import java.util.UUID;

@Repository
public class JdbcAclGrantRepository implements AclGrantRepository {

    private final JdbcTemplate jdbc;

    public JdbcAclGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AclGrant> findBySubjectId(UUID subjectId) {
        return jdbc.query(
                """
                SELECT grant_id, subject_id, subject_type, resource_path, permission, source
                FROM topology.acl_grants
                WHERE subject_id = ?
                ORDER BY resource_path
                """,
                (rs, row) -> new AclGrant(
                        UUID.fromString(rs.getString("grant_id")),
                        UUID.fromString(rs.getString("subject_id")),
                        rs.getString("subject_type"),
                        rs.getString("resource_path"),
                        rs.getString("permission"),
                        rs.getString("source")
                ),
                subjectId
        );
    }

    @Override
    public void deleteBySource(String source) {
        jdbc.update("DELETE FROM topology.acl_grants WHERE source = ?", source);
    }

    @Override
    public void insert(UUID orgId, UUID subjectId, String subjectType,
                       String resourcePath, String permission, String source) {
        jdbc.update("""
                INSERT INTO topology.acl_grants
                  (org_id, subject_id, subject_type, resource_path, permission, source)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                orgId, subjectId, subjectType, resourcePath, permission, source
        );
    }
}
