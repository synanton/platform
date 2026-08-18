package org.synanton.topology.domain.model;

import java.util.List;
import java.util.Map;

public record OrganizationPolicy(
        String tenantId,
        String tier,
        List<String> allowedRegions,
        String rerankMode,
        String rerankModelFamily,
        int candidatePoolSize,
        int rerankTopN,
        double monthlyUsdCap,
        int weight,
        int maxConcurrentIngestJobs,
        int maxContextTokens,
        Map<String, Integer> crossRegionPenaltyMs
) {
    public boolean isHighSecurity() {
        return "HIGH_SECURITY".equals(tier) || "FINANCIAL".equals(tier) || "HEALTHCARE".equals(tier);
    }
}
