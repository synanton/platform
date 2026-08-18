package org.synanton.topology.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.topology.domain.model.OrganizationPolicy;
import org.synanton.topology.infra.jdbc.JdbcOrganizationPolicyRepository;

import java.util.Map;

@RestController
@RequestMapping("/topology")
public class TopologyQueryController {

    private final JdbcOrganizationPolicyRepository policies;

    public TopologyQueryController(JdbcOrganizationPolicyRepository policies) {
        this.policies = policies;
    }

    @GetMapping("/tenants/{tenantId}/organization-policy")
    public OrganizationPolicy getTenantPolicy(@PathVariable String tenantId) {
        return policies.require(tenantId);
    }

    @GetMapping("/tenants/{tenantId}/residency")
    public Map<String, Object> getResidencyPolicy(@PathVariable String tenantId) {
        OrganizationPolicy policy = policies.require(tenantId);
        return Map.of("tenant_id", tenantId, "allowed_regions", policy.allowedRegions());
    }

    @GetMapping("/tenants/{tenantId}/rerank")
    public Map<String, Object> getRerankPolicy(@PathVariable String tenantId) {
        OrganizationPolicy policy = policies.require(tenantId);
        return Map.of(
                "mode", policy.rerankMode(),
                "model_family", policy.rerankModelFamily(),
                "candidate_pool_size", policy.candidatePoolSize(),
                "top_n", policy.rerankTopN()
        );
    }

    @GetMapping("/tenants/{tenantId}/budget")
    public Map<String, Object> getBudgetRemaining(@PathVariable String tenantId) {
        OrganizationPolicy policy = policies.require(tenantId);
        return Map.of(
                "monthly_usd_cap", policy.monthlyUsdCap(),
                "weight", policy.weight(),
                "max_concurrent_ingest_jobs", policy.maxConcurrentIngestJobs()
        );
    }

    @GetMapping("/tenants/{tenantId}/cross-region-penalty")
    public Map<String, Object> getCrossRegionPenaltyMap(@PathVariable String tenantId) {
        OrganizationPolicy policy = policies.require(tenantId);
        return Map.of("tenant_id", tenantId, "penalty_ms", policy.crossRegionPenaltyMs());
    }
}
