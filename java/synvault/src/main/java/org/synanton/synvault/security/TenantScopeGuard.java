package org.synanton.synvault.security;

import org.synanton.common.error.ForbiddenException;
import org.synanton.common.tenant.TenantContext;

import java.util.Set;

public class TenantScopeGuard {

    public void check(String pathTenant) {
        TenantContext ctx = TenantContext.get();
        if (ctx == null) {
            return;
        }
        if (ctx.hasRole("support_admin")) {
            return;
        }
        if (ctx.tenantId() != null && pathTenant != null && !ctx.tenantId().equals(pathTenant)) {
            throw new ForbiddenException("Caller tenant does not match resource tenant", "ERR_TENANT_SCOPE_DENIED");
        }
    }
}
