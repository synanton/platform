package org.synanton.contentcache.postgres;

import java.util.Objects;
import javax.sql.DataSource;
import org.synanton.contentcache.ContentMetadata;
import org.synanton.contentcache.ContentNotFoundException;
import org.synanton.contentcache.ContentReference;
import org.synanton.contentcache.PutResult;
import org.synanton.storage.contract.TenantScope;

/**
 * Tenant-bound facade over the content-cache repository (#339).
 *
 * <p>Scope is a required constructor value, never a ThreadLocal or
 * request-context lookup. A caller cannot invoke a facade method without
 * supplying a validated {@link TenantScope}, and a {@link ContentReference}
 * from another tenant is rejected before it reaches storage.
 *
 * <p><b>Coverage:</b> this facade covers the relational content-cache store
 * ({@code artifacts}, {@code retention_policies}, {@code reference_registry},
 * {@code audit_log}). It does not wrap the search projection or provenance
 * paths; those are covered by their own tenant-scoped tests and the
 * post-rebuild two-tenant acceptance.
 *
 * <p>There is deliberately no raw-query escape hatch. RLS remains the
 * database backstop; the facade is the application-layer required parameter.
 */
public final class TenantScopedContentStore {

    private final JdbcContentStore delegate;
    private final TenantScope scope;

    private TenantScopedContentStore(JdbcContentStore delegate, TenantScope scope) {
        this.delegate = delegate;
        this.scope = scope;
    }

    public static TenantScopedContentStore forTenant(
            DataSource dataSource, int defaultRetentionDays, TenantScope scope) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(scope, "scope");
        return new TenantScopedContentStore(
                new JdbcContentStore(dataSource, defaultRetentionDays), scope);
    }

    public TenantScope scope() {
        return scope;
    }

    public int countByDigest(String digest) {
        requireDigest(digest);
        return delegate.inTenantTransaction(scope.tenantId(), con ->
                delegate.countByDigest(con, scope.tenantId(), digest));
    }

    /**
     * Reads the tenant's count for a digest and writes the cross-tenant audit
     * row in the same transaction. Audit failure rolls back the read.
     */
    public int countByDigestWithAudit(
            String digest,
            String principalId,
            String capability,
            String operation,
            String reason,
            String traceId,
            String auditKey) {
        requireDigest(digest);
        return delegate.inTenantTransaction(scope.tenantId(), con -> {
            int count = delegate.countByDigest(con, scope.tenantId(), digest);
            delegate.writeAudit(con, scope.tenantId(), principalId, capability,
                    operation, reason, traceId, auditKey, count, "allowed");
            return count;
        });
    }

    /** Denial audit; the caller decides whether audit failure aborts. */
    public void auditDenial(
            String principalId,
            String capability,
            String operation,
            String reason,
            String traceId,
            String auditKey) {
        delegate.inTenantTransaction(scope.tenantId(), con -> {
            delegate.writeAudit(con, scope.tenantId(), principalId, capability,
                    operation, reason, traceId, auditKey, 0, "denied");
            return null;
        });
    }

    public PutResult put(byte[] payload, ContentMetadata metadata) {
        return delegate.put(scope.tenantId(), payload, metadata);
    }

    public byte[] get(ContentReference reference) {
        requireOwned(reference);
        return delegate.get(reference);
    }

    public boolean exists(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (!scope.tenantId().equals(reference.tenantId())) {
            return false;
        }
        return delegate.exists(reference);
    }

    public void markForDeletion(ContentReference reference, String ownerId) {
        requireOwned(reference);
        delegate.markForDeletion(reference, ownerId);
    }

    public void registerReference(String digest, String ownerId) {
        delegate.registerReference(scope.tenantId(), digest, ownerId);
    }

    public void releaseReference(String digest, String ownerId) {
        delegate.releaseReference(scope.tenantId(), digest, ownerId);
    }

    public void setLegalHold(ContentReference reference, boolean hold, String actor, String reason) {
        requireOwned(reference);
        delegate.setLegalHold(reference, hold, actor, reason);
    }

    public void setRetention(ContentReference reference, int retentionDays) {
        requireOwned(reference);
        delegate.setRetention(reference, retentionDays);
    }

    private static void requireDigest(String digest) {
        Objects.requireNonNull(digest, "digest");
        if (digest.isBlank()) {
            throw new IllegalArgumentException("digest must not be blank");
        }
    }

    private void requireOwned(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (!scope.tenantId().equals(reference.tenantId())) {
            // Indistinguishable from a missing artifact: a foreign reference
            // must not reveal that it exists in another tenant.
            throw new ContentNotFoundException("artifact not found");
        }
    }
}
