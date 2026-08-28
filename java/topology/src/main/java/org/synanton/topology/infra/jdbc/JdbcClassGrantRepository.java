package org.synanton.topology.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.model.ClassGrant;
import org.synanton.topology.domain.repository.ClassGrantRepository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Repository
public class JdbcClassGrantRepository implements ClassGrantRepository {

    private final JdbcTemplate jdbc;

    public JdbcClassGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ClassGrant> findActiveByTenantAndSubjectKeys(String tenantId, Set<String> subjectKeys) {
        if (subjectKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", subjectKeys.stream().map(k -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(subjectKeys);
        return jdbc.query(
                """
                SELECT grant_id, tenant_id, subject_key, subject_type, class, permission,
                       propagation_state, created_at
                FROM topology.class_grants
                WHERE tenant_id = ? AND revoked_at IS NULL AND subject_key IN (%s)
                ORDER BY created_at DESC
                """.formatted(placeholders),
                (rs, row) -> new ClassGrant(
                        rs.getObject("grant_id", java.util.UUID.class),
                        rs.getString("tenant_id"),
                        rs.getString("subject_key"),
                        rs.getString("subject_type"),
                        rs.getString("class"),
                        rs.getString("permission"),
                        rs.getString("propagation_state"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                args.toArray()
        );
    }

    @Override
    public List<ClassGrant> findActiveByTenant(String tenantId) {
        return jdbc.query(
                """
                SELECT grant_id, tenant_id, subject_key, subject_type, class, permission,
                       propagation_state, created_at
                FROM topology.class_grants
                WHERE tenant_id = ? AND revoked_at IS NULL
                ORDER BY created_at DESC
                """,
                (rs, row) -> new ClassGrant(
                        rs.getObject("grant_id", java.util.UUID.class),
                        rs.getString("tenant_id"),
                        rs.getString("subject_key"),
                        rs.getString("subject_type"),
                        rs.getString("class"),
                        rs.getString("permission"),
                        rs.getString("propagation_state"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                tenantId
        );
    }

    /**
     * Effective sensitivity classes for a caller: {@code PUBLIC} plus any active grants
     * matching the user key or group/role keys.
     */
    public Set<String> resolveCallerClasses(String tenantId, String subjectId, Set<String> groupKeys) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        keys.add(subjectId);
        keys.addAll(groupKeys);
        Set<String> classes = new LinkedHashSet<>();
        classes.add("PUBLIC");
        for (ClassGrant grant : findActiveByTenantAndSubjectKeys(tenantId, keys)) {
            classes.add(grant.sensitivityClass());
        }
        return Set.copyOf(classes);
    }
}
