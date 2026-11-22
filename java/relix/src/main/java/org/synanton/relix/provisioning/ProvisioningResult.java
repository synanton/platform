package org.synanton.relix.provisioning;

import java.util.List;

public record ProvisioningResult(
        String tenantId,
        String providerRequested,
        String cliVersion,
        String gitSha,
        String operator,
        String startedAt,
        String completedAt,
        String outcome,
        List<ProvisioningStep> steps,
        String failureReason) {
}
