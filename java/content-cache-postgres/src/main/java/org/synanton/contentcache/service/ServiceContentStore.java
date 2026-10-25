package org.synanton.contentcache.service;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
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
    private final LongAdder denialAuditFailures = new LongAdder();

    public ServiceContentStore(TenantScopedContentStore delegate, ServicePrincipal principal) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.principal = Objects.requireNonNull(principal, "principal");
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
        return denialAuditFailures.sum();
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
                denialAuditFailures.increment();
            }
            throw new ServiceAuthorizationException(
                    "service principal lacks " + ServiceCapability.CONTENT_MUTATE_TENANT);
        }
    }
}
