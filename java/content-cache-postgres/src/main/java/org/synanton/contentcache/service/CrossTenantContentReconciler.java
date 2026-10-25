package org.synanton.contentcache.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
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
    private final LongAdder denialAuditFailures = new LongAdder();

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
            // Expiry is a denial, not a distinguishable error: zero rows keeps
            // the same anti-oracle behavior as a missing capability.
            return deny(request, "service principal expired");
        }
        if (!principal.has(ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT)) {
            return deny(request, "missing cross-tenant capability");
        }
        Map<TenantScope, Integer> counts = new LinkedHashMap<>();
        Map<TenantScope, TenantOutcome> outcomes = new LinkedHashMap<>();
        RuntimeException firstFailure = null;
        for (TenantScope scope : request.tenants()) {
            try {
                int count = store(scope).countByDigestWithAudit(
                        digest,
                        principal.principalId(),
                        ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT.name(),
                        request.operation(),
                        request.reason(),
                        request.traceId(),
                        auditKey(request, scope));
                counts.put(scope, count);
                outcomes.put(scope, TenantOutcome.success(count));
            } catch (RuntimeException failure) {
                outcomes.put(scope, TenantOutcome.failure(failure.getMessage()));
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
        }
        if (firstFailure != null) {
            throw new PartialReconcileException(outcomes, firstFailure);
        }
        return new ReconcileReport(counts);
    }

    /** Denied before any read; denial-audit failure stays zero rows. */
    private ReconcileReport deny(CrossTenantRequest request, String detail) {
        ServicePrincipal principal = request.principal();
        for (TenantScope scope : request.tenants()) {
            try {
                store(scope).auditDenial(
                        principal.principalId(),
                        ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT.name(),
                        request.operation(),
                        request.reason() + ": " + detail,
                        request.traceId(),
                        auditKey(request, scope));
            } catch (RuntimeException ignored) {
                denialAuditFailures.increment();
            }
        }
        return ReconcileReport.empty();
    }

    private static String auditKey(CrossTenantRequest request, TenantScope scope) {
        return request.principal().principalId()
                + "|" + ServiceCapability.CONTENT_RECONCILE_CROSS_TENANT.name()
                + "|" + scope.tenantId()
                + "|" + request.operation()
                + "|" + request.operationId();
    }

    /** Counter for the otherwise-silent denial-audit failure path. */
    public long denialAuditFailures() {
        return denialAuditFailures.sum();
    }

    private TenantScopedContentStore store(TenantScope scope) {
        return TenantScopedContentStore.forTenant(dataSource, defaultRetentionDays, scope);
    }
}
