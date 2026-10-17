package org.synanton.synflux.security;

import org.synanton.common.tenant.TenantContext;
import org.synanton.synflux.lifecycle.VerifiedIngestionContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class TrustedRequestContext {

    private TrustedRequestContext() {}

    public static VerifiedIngestionContext requireIngestionContext() {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()
                || context.subject() == null || context.subject().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Verified tenant context is required");
        }
        return new VerifiedIngestionContext(context.tenantId(), context.subject(), "USER");
    }
}
