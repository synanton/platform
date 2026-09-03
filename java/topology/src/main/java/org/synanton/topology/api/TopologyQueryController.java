package org.synanton.topology.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.topology.domain.model.ClassGrant;
import org.synanton.topology.domain.model.OrganizationPolicy;
import org.synanton.topology.infra.jdbc.JdbcClassGrantRepository;
import org.synanton.topology.infra.jdbc.JdbcOrganizationPolicyRepository;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/topology")
public class TopologyQueryController {

    private final JdbcOrganizationPolicyRepository policies;
    private final JdbcClassGrantRepository classGrants;

    public TopologyQueryController(
            JdbcOrganizationPolicyRepository policies,
            JdbcClassGrantRepository classGrants
    ) {
        this.policies = policies;
        this.classGrants = classGrants;
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

    @GetMapping("/tenants/{tenantId}/subjects/{subjectId}/classes")
    public Map<String, Object> resolveCallerClasses(
            @PathVariable String tenantId,
            @PathVariable String subjectId,
            @RequestParam(required = false) String groups
    ) {
        Set<String> groupKeys = new LinkedHashSet<>();
        if (groups != null && !groups.isBlank()) {
            Arrays.stream(groups.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(groupKeys::add);
        }
        Set<String> classes = classGrants.resolveCallerClasses(tenantId, subjectId, groupKeys);
        return Map.of(
                "tenant_id", tenantId,
                "subject_id", subjectId,
                "classes", classes
        );
    }

    @GetMapping("/tenants/{tenantId}/class-grants")
    public List<ClassGrant> listClassGrants(@PathVariable String tenantId) {
        return classGrants.findActiveByTenant(tenantId);
    }
}
