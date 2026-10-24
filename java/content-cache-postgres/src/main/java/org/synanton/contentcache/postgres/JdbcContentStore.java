package org.synanton.contentcache.postgres;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.synanton.contentcache.ContentIntegrityException;
import org.synanton.contentcache.ContentMetadata;
import org.synanton.contentcache.ContentNotFoundException;
import org.synanton.contentcache.ContentReference;
import org.synanton.contentcache.ContentStore;
import org.synanton.contentcache.ContentStoreException;
import org.synanton.contentcache.PutResult;

/**
 * JDBC {@link ContentStore} against the canonical content-cache schema.
 *
 * <p>Semantics mirror the Go reference adapter: per-tenant dedup by
 * digest, indistinguishable cross-tenant not-found, digest verification
 * on read, owner-scoped reference release, audited legal-hold changes.
 */
public final class JdbcContentStore implements ContentStore {

    private final DataSource dataSource;
    private final int defaultRetentionDays;

    public JdbcContentStore(DataSource dataSource, int defaultRetentionDays) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (defaultRetentionDays <= 0) {
            throw new IllegalArgumentException("defaultRetentionDays must be positive");
        }
        this.defaultRetentionDays = defaultRetentionDays;
    }

    public static String sha256(byte[] payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(payload);
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public PutResult put(String tenantId, byte[] payload, ContentMetadata metadata) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(metadata, "metadata");
        if (payload.length == 0) {
            throw new ContentStoreException("payload is empty");
        }
        String digest = sha256(payload);
        int retentionDays = metadata.retentionDays() > 0 ? metadata.retentionDays() : defaultRetentionDays;
        return inTenantTransaction(tenantId, con -> {
            String existing = findArtifactId(con, tenantId, digest);
            if (existing != null) {
                upsertReference(con, tenantId, digest, metadata.ownerId());
                return new PutResult(
                        new ContentReference(
                                tenantId, existing, digest, 1, metadata.serializerVersion()),
                        true);
            }
            String artifactId = UUID.randomUUID().toString();
            OffsetDateTime expiresAt =
                    OffsetDateTime.now(ZoneOffset.UTC).plus(retentionDays, ChronoUnit.DAYS);
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO artifacts (tenant_id, artifact_id, digest, generation,"
                            + " serializer_version, content_type, size_bytes, storage_tier,"
                            + " payload, expires_at)"
                            + " VALUES (?, ?::uuid, ?, 1, ?, ?, ?, 'inline', ?, ?)"
                            + " ON CONFLICT DO NOTHING")) {
                ps.setString(1, tenantId);
                ps.setString(2, artifactId);
                ps.setString(3, digest);
                ps.setInt(4, metadata.serializerVersion());
                ps.setString(5, metadata.contentType());
                ps.setLong(6, payload.length);
                ps.setBytes(7, payload);
                ps.setObject(8, expiresAt);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO retention_policies (tenant_id, artifact_id, owner_id,"
                            + " retention_days, expires_at, legal_hold)"
                            + " VALUES (?, ?::uuid, ?, ?, ?, false)"
                            + " ON CONFLICT (tenant_id, artifact_id, owner_id) DO UPDATE"
                            + " SET retention_days = EXCLUDED.retention_days,"
                            + " expires_at = EXCLUDED.expires_at, updated_at = now()")) {
                ps.setString(1, tenantId);
                ps.setString(2, artifactId);
                ps.setString(3, metadata.ownerId());
                ps.setInt(4, retentionDays);
                ps.setObject(5, expiresAt);
                ps.executeUpdate();
            }
            upsertReference(con, tenantId, digest, metadata.ownerId());
            ContentReference ref = new ContentReference(
                    tenantId, artifactId, digest, 1, metadata.serializerVersion());
            if (ref.encode().length() > ContentReference.MAX_LENGTH) {
                throw new ContentStoreException("encoded reference exceeds 512 characters");
            }
            return new PutResult(ref, false);
        });
    }

    @Override
    public byte[] get(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        return inTenantTransaction(reference.tenantId(), con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT digest, payload FROM artifacts"
                            + " WHERE tenant_id = ? AND artifact_id = ?::uuid")) {
                ps.setString(1, reference.tenantId());
                ps.setString(2, reference.artifactId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ContentNotFoundException("artifact not found");
                    }
                    String storedDigest = rs.getString(1);
                    byte[] payload = rs.getBytes(2);
                    if (!storedDigest.equals(reference.digest()) || !sha256(payload).equals(storedDigest)) {
                        throw new ContentIntegrityException(
                                "digest mismatch for artifact " + reference.artifactId());
                    }
                    return Arrays.copyOf(payload, payload.length);
                }
            }
        });
    }

    @Override
    public boolean exists(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        return inTenantTransaction(reference.tenantId(), con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT 1 FROM artifacts WHERE tenant_id = ? AND artifact_id = ?::uuid")) {
                ps.setString(1, reference.tenantId());
                ps.setString(2, reference.artifactId());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public void markForDeletion(ContentReference reference, String ownerId) {
        Objects.requireNonNull(reference, "reference");
        String digest = digestFor(reference);
        releaseReference(reference.tenantId(), digest, ownerId);
    }

    @Override
    public void registerReference(String tenantId, String digest, String ownerId) {
        Objects.requireNonNull(digest, "digest");
        inTenantTransaction(tenantId, con -> {
            upsertReference(con, tenantId, digest, ownerId);
            return null;
        });
    }

    @Override
    public void releaseReference(String tenantId, String digest, String ownerId) {
        Objects.requireNonNull(digest, "digest");
        inTenantTransaction(tenantId, con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE reference_registry SET released_at = now()"
                            + " WHERE tenant_id = ? AND digest = ? AND owner_id = ?"
                            + " AND released_at IS NULL")) {
                ps.setString(1, tenantId);
                ps.setString(2, digest);
                ps.setString(3, ownerId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void setLegalHold(ContentReference reference, boolean hold, String actor, String reason) {
        Objects.requireNonNull(reference, "reference");
        if (reason == null || reason.isBlank()) {
            throw new ContentStoreException("reason is required for legal hold changes");
        }
        inTenantTransaction(reference.tenantId(), con -> {
            int updated;
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE retention_policies SET legal_hold = ?, updated_at = now()"
                            + " WHERE tenant_id = ? AND artifact_id = ?::uuid")) {
                ps.setBoolean(1, hold);
                ps.setString(2, reference.tenantId());
                ps.setString(3, reference.artifactId());
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                throw new ContentNotFoundException("artifact not found");
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO audit_log (actor, action, tenant_id, artifact_id, scope, reason)"
                            + " VALUES (?, ?, ?, ?::uuid, 'artifact', ?)")) {
                ps.setString(1, actor);
                ps.setString(2, hold ? "legal_hold_place" : "legal_hold_release");
                ps.setString(3, reference.tenantId());
                ps.setString(4, reference.artifactId());
                ps.setString(5, reason);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void setRetention(ContentReference reference, int retentionDays) {
        Objects.requireNonNull(reference, "reference");
        if (retentionDays <= 0) {
            throw new ContentStoreException("retentionDays must be positive");
        }
        inTenantTransaction(reference.tenantId(), con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE retention_policies SET retention_days = ?,"
                            + " expires_at = now() + (? * interval '1 day'), updated_at = now()"
                            + " WHERE tenant_id = ? AND artifact_id = ?::uuid")) {
                ps.setInt(1, retentionDays);
                ps.setInt(2, retentionDays);
                ps.setString(3, reference.tenantId());
                ps.setString(4, reference.artifactId());
                if (ps.executeUpdate() == 0) {
                    throw new ContentNotFoundException("artifact not found");
                }
            }
            return null;
        });
    }

    private String findArtifactId(Connection con, String tenantId, String digest) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT artifact_id::text FROM artifacts WHERE tenant_id = ? AND digest = ?")) {
            ps.setString(1, tenantId);
            ps.setString(2, digest);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String digestFor(ContentReference reference) {
        return inTenantTransaction(reference.tenantId(), con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT digest FROM artifacts WHERE tenant_id = ? AND artifact_id = ?::uuid")) {
                ps.setString(1, reference.tenantId());
                ps.setString(2, reference.artifactId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ContentNotFoundException("artifact not found");
                    }
                    return rs.getString(1);
                }
            }
        });
    }

    private void upsertReference(Connection con, String tenantId, String digest, String ownerId)
            throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO reference_registry (tenant_id, digest, owner_id, owner_type)"
                        + " VALUES (?, ?, ?, 'commitix_intent')"
                        + " ON CONFLICT (tenant_id, digest, owner_id) DO UPDATE"
                        + " SET released_at = NULL")) {
            ps.setString(1, tenantId);
            ps.setString(2, digest);
            ps.setString(3, ownerId);
            ps.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface TenantWork<T> {
        T run(Connection con) throws Exception;
    }

    /**
     * Every content-cache operation runs inside an explicit transaction with
     * transaction-scoped {@code app.tenant_id}. The SQL predicates remain as
     * defense in depth; RLS is the fail-closed guarantee for code that bypasses
     * this repository.
     */
    private <T> T inTenantTransaction(String tenantId, TenantWork<T> work) {
        requireTenant(tenantId);
        try (Connection con = dataSource.getConnection()) {
            boolean autoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT set_config('app.tenant_id', ?, true)")) {
                    ps.setString(1, tenantId);
                    ps.execute();
                }
                T result = work.run(con);
                con.commit();
                return result;
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(autoCommit);
            }
        } catch (ContentStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new ContentStoreException("tenant-scoped operation failed", e);
        }
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ContentStoreException("tenant_id is required");
        }
    }
}
