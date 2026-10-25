package org.synanton.contentcache.service;

import java.util.Objects;
import java.util.Set;
import org.synanton.storage.contract.TenantScope;

/**
 * Explicit bounded cross-tenant request. The tenant set is enumerated; there is
 * no wildcard, ALL sentinel, empty set, or more than
 * {@link #MAX_CROSS_TENANT_SCOPE} entries. Capability is checked at execution,
 * not construction, so an unauthorized caller can receive zero rows.
 */
public record CrossTenantRequest(
        ServicePrincipal principal,
        Set<TenantScope> tenants,
        String operation,
        String reason,
        String traceId) {

    public static final int MAX_CROSS_TENANT_SCOPE = 100;

    public CrossTenantRequest {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(tenants, "tenants");
        if (tenants.isEmpty()) {
            throw new IllegalArgumentException("cross-tenant set must not be empty");
        }
        if (tenants.size() > MAX_CROSS_TENANT_SCOPE) {
            throw new IllegalArgumentException(
                    "cross-tenant set exceeds " + MAX_CROSS_TENANT_SCOPE + "; paginate");
        }
        for (TenantScope scope : tenants) {
            String id = scope.tenantId();
            if (id.equals("*") || id.equalsIgnoreCase("ALL")) {
                throw new IllegalArgumentException("wildcard tenant scope is not permitted");
            }
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        tenants = Set.copyOf(tenants);
    }
}
