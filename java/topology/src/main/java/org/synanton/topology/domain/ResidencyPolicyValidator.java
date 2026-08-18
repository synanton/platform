package org.synanton.topology.domain;

import java.util.Set;

public class ResidencyPolicyValidator {

    private final Set<String> registeredRegions;

    public ResidencyPolicyValidator(Set<String> registeredRegions) {
        this.registeredRegions = Set.copyOf(registeredRegions);
    }

    public void validate(Iterable<String> allowedRegions, boolean force, boolean contentInDroppedRegion) {
        boolean empty = true;
        for (String region : allowedRegions) {
            empty = false;
            if (!registeredRegions.contains(region)) {
                throw new IllegalArgumentException("Region not registered: " + region);
            }
        }
        if (empty) {
            throw new IllegalArgumentException("allowed_regions must be non-empty");
        }
        if (contentInDroppedRegion && !force) {
            throw new ResidencyDowngradeException("force=true required to drop a region that still has content");
        }
    }

    public static class ResidencyDowngradeException extends RuntimeException {
        public ResidencyDowngradeException(String message) {
            super(message);
        }
    }
}
