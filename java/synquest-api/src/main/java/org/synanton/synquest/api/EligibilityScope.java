package org.synanton.synquest.api;

import org.synanton.storage.contract.SecurityContext;
import org.synanton.storage.contract.StorageErrorKind;
import org.synanton.storage.contract.StorageException;
import org.synanton.storage.contract.TenantScope;

/**
 * Request-vs-context scope gate (P0-3). Request-sourced inputs must never broaden
 * or bypass the validated security context — in particular, a service context
 * must not adopt the request's eligibility tenant (service-context broadening).
 *
 * <p>Rule: the effective tenant is ALWAYS the context tenant. A request whose
 * eligibility names any other tenant is rejected with {@code FORBIDDEN},
 * including under service contexts. Cross-tenant delegation flows are 025b
 * territory and are not silently permitted.
 */
public final class EligibilityScope {
    private EligibilityScope() {}

    public static TenantScope effectiveTenant(
            SecurityContext context, EligibilityConstraints eligibility) {
        TenantScope requested = eligibility.tenantScope();
        TenantScope validated = context.tenantScope();
        if (!requested.equals(validated)) {
            throw new StorageException(
                    StorageErrorKind.FORBIDDEN,
                    "FORBIDDEN: request eligibility tenant '" + requested.tenantId()
                            + "' does not match validated context tenant '" + validated.tenantId()
                            + "'; service=" + context.service()
                            + " principals=" + eligibility.principals());
        }
        return validated;
    }
}
