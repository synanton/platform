package org.synanton.relix.service;

import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.tenant.MissingTenantContextException;
import org.synanton.common.tenant.TenantContext;
import org.synanton.common.tenant.TenantIdentityMismatchException;

final class TenantIdentityGuard {

    private TenantIdentityGuard() {}

    static String requireRequestTenant(String requestedTenant, GraphIdentity identity) {
        if (requestedTenant == null || requestedTenant.isBlank()) {
            throw new MissingTenantContextException("graph operation requires an explicit tenant");
        }
        if (identity == null || !requestedTenant.equals(identity.tenantId())) {
            throw new TenantIdentityMismatchException(
                    "graph identity does not match the requested tenant");
        }
        TenantContext context = TenantContext.get();
        if (context != null && context.tenantId() != null && !context.tenantId().isBlank()
                && !context.tenantId().equals(requestedTenant)) {
            throw new TenantIdentityMismatchException(
                    "request tenant does not match the verified identity");
        }
        return requestedTenant;
    }
}
