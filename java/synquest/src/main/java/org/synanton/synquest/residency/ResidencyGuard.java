package org.synanton.synquest.residency;

import java.io.Serial;
import java.util.Set;

public class ResidencyGuard {

    public void assertAllowed(String requestRegion, Set<String> allowedRegions) {
        if (requestRegion == null || requestRegion.isBlank()) {
            return;
        }
        if (allowedRegions != null && !allowedRegions.isEmpty() && !allowedRegions.contains(requestRegion)) {
            throw new ResidencyDeniedException(requestRegion, allowedRegions);
        }
    }

    public static class ResidencyDeniedException extends RuntimeException {
        @Serial
        static final long serialVersionUID = 1L;

        public ResidencyDeniedException(String region, Set<String> allowed) {
            super("residency denied for region " + region + " allowed=" + allowed);
        }
    }
}
