package org.synanton.contentcache.service;

import java.util.Map;
import org.synanton.storage.contract.TenantScope;

/**
 * N-transaction reconcile can succeed for some tenants and fail for others.
 * The error carries every tenant's outcome so the caller can retry only the
 * failures; already-committed tenants are idempotent on retry.
 */
@SuppressWarnings("serial")
public class PartialReconcileException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final Map<TenantScope, TenantOutcome> outcomes;

    public PartialReconcileException(Map<TenantScope, TenantOutcome> outcomes, Throwable cause) {
        super("cross-tenant reconcile partially failed", cause);
        this.outcomes = Map.copyOf(outcomes);
    }

    public Map<TenantScope, TenantOutcome> outcomes() {
        return outcomes;
    }
}
