package org.synanton.topology.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.model.Tenant;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcTenantRepository {

    private final JdbcTemplate jdbc;

    public JdbcTenantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Tenant> findAll() {
        return jdbc.query(
                "SELECT tenant_id, display_name, created_at FROM topology.tenants ORDER BY tenant_id",
                tenantMapper()
        );
    }

    public Optional<Tenant> findById(String tenantId) {
        List<Tenant> results = jdbc.query(
                "SELECT tenant_id, display_name, created_at FROM topology.tenants WHERE tenant_id = ?",
                tenantMapper(),
                tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void insert(Tenant tenant) {
        jdbc.update(
                "INSERT INTO topology.tenants (tenant_id, display_name, created_at) VALUES (?, ?, ?)",
                tenant.tenantId(),
                tenant.displayName(),
                tenant.createdAt() != null ? java.sql.Timestamp.from(tenant.createdAt()) : null
        );
    }

    public boolean exists(String tenantId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM topology.tenants WHERE tenant_id = ?",
                Integer.class,
                tenantId
        );
        return count != null && count > 0;
    }

    private RowMapper<Tenant> tenantMapper() {
        return (rs, row) -> new Tenant(
                rs.getString("tenant_id"),
                rs.getString("display_name"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
