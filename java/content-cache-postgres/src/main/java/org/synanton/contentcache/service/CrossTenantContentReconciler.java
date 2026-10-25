package org.synanton.contentcache.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.synanton.contentcache.postgres.TenantScopedContentStore;
import org.synanton.storage.contract.TenantScope;

/**
 * Bounded cross-tenant content reconcile. Each tenant gets its own facade read
 * and its own transaction; the audit line is written in that transaction.
 * There is no global query, no null tenant, and no RLS bypass.
 */
public final class CrossTenantContentReconciler {

    private final DataSource dataSource;
    private final int defaultRetentionDays;

    public CrossTenantContentReconciler(DataSource dataSource, int defaultRetentionDays) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.defaultRetentionDays = defaultRetentionDays;
    }

    public ReconcileReport reconcile(CrossTenantRequest request, String digest) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(digest, "digest");
        if (digest.isBlank()) {
            throw new IllegalArgumentException("digest must not be blank");
        }
        ServicePrincipal principal = request.principal();
        if (principal.expired(Instant.now())) {
            throw new ServiceAuthorizationException("service principal expired");
        }
        if (!principal.has(ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT)) {
            // Denied before any read. Denial audit failure is deliberately
            // swallowed so the caller still gets zero rows, not an error.
            for (TenantScope scope : request.tenants()) {
                try {
                    store(scope).auditDenial(
                            principal.principalId(),
                            ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT.name(),
                            request.operation(),
                            request.reason(),
                            request.traceId());
                } catch (RuntimeException ignored) {
                    // no data exposed; denial remains zero rows
                }
            }
            return ReconcileReport.empty();
        }
        Map<TenantScope, Integer> counts = new LinkedHashMap<>();
        for (TenantScope scope : request.tenants()) {
            int count = store(scope).countByDigestWithAudit(
                    digest,
                    principal.principalId(),
                    ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT.name(),
                    request.operation(),
                    request.reason(),
                    request.traceId());
            counts.put(scope, count);
        }
        return new ReconcileReport(counts);
    }

    private TenantScopedContentStore store(TenantScope scope) {
        return TenantScopedContentStore.forTenant(dataSource, defaultRetentionDays, scope);
    }
}
