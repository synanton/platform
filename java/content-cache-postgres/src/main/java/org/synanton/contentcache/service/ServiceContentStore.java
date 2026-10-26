package org.synanton.contentcache.service;
import org.synanton.storage.contract.service.ServiceAuthorizationException;
import org.synanton.storage.contract.service.ServiceCapability;
import org.synanton.storage.contract.service.ServicePrincipal;

import java.util.Objects;
import org.synanton.contentcache.ContentReference;
import org.synanton.contentcache.postgres.TenantScopedContentStore;

/**
 * Tenant-bound service mutation gate. Wraps the same facade; there is no
 * second store path. Missing mutation capability is an explicit error before
 * any write, not a silent no-op.
 */
public final class ServiceContentStore {

    private final TenantScopedContentStore delegate;
    private final ServicePrincipal principal;
    private final ServiceAuditMetrics metrics;

    public ServiceContentStore(TenantScopedContentStore delegate, ServicePrincipal principal) {
        this(delegate, principal, new ServiceAuditMetrics());
    }

    public ServiceContentStore(
            TenantScopedContentStore delegate, ServicePrincipal principal, ServiceAuditMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public void setRetention(ContentReference reference, int retentionDays) {
        requireMutation();
        delegate.setRetention(reference, retentionDays);
    }

    public void markForDeletion(ContentReference reference, String ownerId) {
        requireMutation();
        delegate.markForDeletion(reference, ownerId);
    }

    public void setLegalHold(ContentReference reference, boolean hold, String actor, String reason) {
        requireMutation();
        delegate.setLegalHold(reference, hold, actor, reason);
    }

    /** Counter for the otherwise-silent mutation-denial audit failure. */
    public long denialAuditFailures() {
        return metrics.denialWriteFailures();
    }

    private void requireMutation() {
        if (!principal.has(ServiceCapability.CONTENT_MUTATE_TENANT)) {
            try {
                delegate.auditDenial(
                        principal.principalId(),
                        ServiceCapability.CONTENT_MUTATE_TENANT.name(),
                        "mutation",
                        "service principal lacks mutation capability",
                        "n/a",
                        null);
            } catch (RuntimeException ignored) {
                // Denial remains explicit; the loss is counted for alerting.
                metrics.denialWriteFailed();
            }
            throw new ServiceAuthorizationException(
                    "service principal lacks " + ServiceCapability.CONTENT_MUTATE_TENANT);
        }
    }
}
