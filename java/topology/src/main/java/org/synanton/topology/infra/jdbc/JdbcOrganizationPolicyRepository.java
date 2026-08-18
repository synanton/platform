package org.synanton.topology.infra.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.common.error.NotFoundException;
import org.synanton.topology.domain.model.OrganizationPolicy;

import java.util.List;
import java.util.Map;

@Repository
public class JdbcOrganizationPolicyRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcOrganizationPolicyRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public OrganizationPolicy require(String tenantId) {
        List<OrganizationPolicy> rows = jdbc.query(
                """
                SELECT tenant_id, tier, data_residency_policy, rerank_policy, budget_policy,
                       cross_region_penalty_ms, max_context_tokens
                FROM topology.organizations
                WHERE tenant_id = ?
                """,
                (rs, row) -> toPolicy(
                        rs.getString("tenant_id"),
                        rs.getString("tier"),
                        rs.getString("data_residency_policy"),
                        rs.getString("rerank_policy"),
                        rs.getString("budget_policy"),
                        rs.getString("cross_region_penalty_ms"),
                        rs.getInt("max_context_tokens")
                ),
                tenantId
        );
        if (rows.isEmpty()) {
            throw new NotFoundException("No organization policy for tenant=" + tenantId);
        }
        return rows.getFirst();
    }

    public void updateAllowedRegions(String tenantId, List<String> allowedRegions) {
        try {
            String json = mapper.writeValueAsString(Map.of("allowed_regions", allowedRegions));
            jdbc.update(
                    "UPDATE topology.organizations SET data_residency_policy = CAST(? AS jsonb), updated_at = now() WHERE tenant_id = ?",
                    json,
                    tenantId
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update residency policy", e);
        }
    }

    private OrganizationPolicy toPolicy(
            String tenantId,
            String tier,
            String residencyJson,
            String rerankJson,
            String budgetJson,
            String penaltyJson,
            int maxContextTokens
    ) {
        List<String> regions = readList(residencyJson, "allowed_regions");
        Map<String, Object> rerank = readMap(rerankJson);
        Map<String, Object> budget = readMap(budgetJson);
        Map<String, Integer> penalties = new java.util.LinkedHashMap<>();
        readMap(penaltyJson).forEach((key, value) -> {
            if (value instanceof Number number) {
                penalties.put(key, number.intValue());
            }
        });
        return new OrganizationPolicy(
                tenantId,
                tier == null ? "STANDARD" : tier,
                regions.isEmpty() ? List.of("us-east-1") : regions,
                String.valueOf(rerank.getOrDefault("mode", "ALWAYS")),
                String.valueOf(rerank.getOrDefault("model_family", "bge-reranker-large")),
                asInt(rerank.get("candidate_pool_size"), 100),
                asInt(rerank.get("top_n"), 20),
                asDouble(budget.get("monthly_usd_cap"), 1000),
                asInt(budget.get("weight"), 100),
                asInt(budget.get("max_concurrent_ingest_jobs"), 8),
                maxContextTokens <= 0 ? 32000 : maxContextTokens,
                penalties
        );
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readList(String json, String key) {
        Object value = readMap(json).get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
