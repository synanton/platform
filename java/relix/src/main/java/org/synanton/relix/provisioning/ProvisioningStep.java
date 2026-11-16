package org.synanton.relix.provisioning;

import java.util.Map;

public record ProvisioningStep(
        String name,
        String outcome,
        long durationMs,
        Map<String, Object> details) {
}
