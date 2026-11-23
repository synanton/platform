package org.synanton.ingestioncache.compliance;

import java.util.UUID;

/** Phase 2b hook for capture/diagnostic artifact cleanup; returns artifacts removed. */
@FunctionalInterface
public interface CaptureCleanup {
    int deleteCapture(String tenantId, UUID sourceVersionId);
}
