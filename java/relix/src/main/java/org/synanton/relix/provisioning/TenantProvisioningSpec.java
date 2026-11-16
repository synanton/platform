package org.synanton.relix.provisioning;

/** Request to provision one isolated evaluation tenant. */
public record TenantProvisioningSpec(
        String tenantId,
        String provider,
        String ontologyVersion,
        byte[] ontologyTtl,
        String operator,
        String gitSha) {
}
