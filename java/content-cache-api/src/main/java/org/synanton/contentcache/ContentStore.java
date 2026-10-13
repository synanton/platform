package org.synanton.contentcache;

/**
 * Commitix port for optional external payload storage (proposal §4.1).
 *
 * <p>{@code commitix-core} must not depend on this interface's
 * implementations; the Java adapter lives behind this port in its own
 * module. References are opaque: adapters treat {@link ContentReference}
 * as a tenant-scoped handle and never parse storage internals out of it.
 */
public interface ContentStore {

    /**
     * Publishes an immutable payload. Idempotent by
     * {@code (tenantId, digest)}: republishing identical bytes for the
     * same tenant returns the existing reference with {@code dedupHit}.
     */
    PutResult put(String tenantId, byte[] payload, ContentMetadata metadata);

    /** Retrieves payload bytes after integrity verification. */
    byte[] get(ContentReference reference);

    /** Existence check without fetching bytes. */
    boolean exists(ContentReference reference);

    /**
     * Releases one owner's logical reference (proposal §3.2). This is a
     * reference release, never a delete request: physical disposition
     * additionally requires ref count zero, no legal hold, and grace
     * period expiry.
     */
    void markForDeletion(ContentReference reference, String ownerId);

    /** Registers an additional owner against an existing digest. */
    void registerReference(String tenantId, String digest, String ownerId);

    /** Releases one owner's registration against a digest. */
    void releaseReference(String tenantId, String digest, String ownerId);

    /** Places or releases a legal hold; changes are audited with actor and reason. */
    void setLegalHold(ContentReference reference, boolean hold, String actor, String reason);

    /** Updates the owner retention window for an artifact. */
    void setRetention(ContentReference reference, int retentionDays);
}
