package org.synanton.topology.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.model.TenantPolicy;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcPolicyRepository {

    private final JdbcTemplate jdbc;

    public JdbcPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantPolicy> findById(String tenantId) {
        List<TenantPolicy> results = jdbc.query(
                """
                SELECT tenant_id, qps_limit, monthly_usd_limit, max_latency_ms
                FROM topology.tenant_policies
                WHERE tenant_id = ?
                """,
                policyMapper(),
                tenantId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void upsert(TenantPolicy policy) {
        jdbc.update(
                """
                INSERT INTO topology.tenant_policies
                  (tenant_id, qps_limit, monthly_usd_limit, max_latency_ms)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET
                  qps_limit = EXCLUDED.qps_limit,
                  monthly_usd_limit = EXCLUDED.monthly_usd_limit,
                  max_latency_ms = EXCLUDED.max_latency_ms,
                  effective_from = now()
                """,
                policy.tenantId(),
                policy.qpsLimit(),
                policy.monthlyUsdLimit(),
                policy.maxLatencyMs()
        );
    }

    private RowMapper<TenantPolicy> policyMapper() {
        return (rs, row) -> new TenantPolicy(
                rs.getString("tenant_id"),
                rs.getInt("qps_limit"),
                rs.getBigDecimal("monthly_usd_limit"),
                rs.getInt("max_latency_ms")
        );
    }
}
