package org.synanton.planner.service;

import java.util.Set;

public class RerankPolicySelector {

    public record Decision(String mode, String modelFamily, boolean callerOverrideApplied) {}

    public Decision select(String tenantMode, String callerHint, String modelFamily) {
        String mode = tenantMode == null ? "ALWAYS" : tenantMode;
        if ("CALLER_REQUESTED".equals(mode) && callerHint != null && !callerHint.isBlank()) {
            return new Decision(callerHint, modelFamily, true);
        }
        return new Decision(mode, modelFamily, false);
    }

    public boolean regionAllowed(String region, Set<String> allowedRegions) {
        return allowedRegions == null || allowedRegions.isEmpty() || allowedRegions.contains(region);
    }

    public int crossRegionPenalty(int localCost, int penaltyMs) {
        return localCost + Math.max(0, penaltyMs);
    }
}
