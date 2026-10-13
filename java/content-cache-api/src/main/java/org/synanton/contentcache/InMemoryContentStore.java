package org.synanton.contentcache;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link ContentStore} for unit tests and local development.
 * Mirrors the Postgres semantics under test: per-tenant dedup by digest,
 * indistinguishable cross-tenant not-found, digest verification on read,
 * and owner-scoped reference release.
 */
public final class InMemoryContentStore implements ContentStore {

    private record Stored(byte[] payload, String digest, String contentType, int serializerVersion) {}

    private final Map<String, Stored> artifacts = new HashMap<>();
    private final Map<String, Set<String>> owners = new HashMap<>();
    private final Map<String, Boolean> legalHold = new HashMap<>();

    private static String key(String tenantId, String artifactId) {
        return tenantId + "\0" + artifactId;
    }

    private static String digestKey(String tenantId, String digest) {
        return tenantId + "\0" + digest;
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
    public synchronized PutResult put(String tenantId, byte[] payload, ContentMetadata metadata) {
        requireTenant(tenantId);
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(metadata, "metadata");
        if (payload.length == 0) {
            throw new ContentStoreException("payload is empty");
        }
        String digest = sha256(payload);
        for (Map.Entry<String, Stored> e : artifacts.entrySet()) {
            if (e.getKey().startsWith(tenantId + "\0") && e.getValue().digest().equals(digest)) {
                String artifactId = e.getKey().substring(tenantId.length() + 1);
                owners.computeIfAbsent(digestKey(tenantId, digest), k -> new HashSet<>()).add(metadata.ownerId());
                ContentReference ref = new ContentReference(
                        tenantId, artifactId, digest, 1, metadata.serializerVersion());
                if (ref.encode().length() > ContentReference.MAX_LENGTH) {
                    throw new ContentStoreException("encoded reference exceeds 512 characters");
                }
                return new PutResult(ref, true);
            }
        }
        String artifactId = UUID.randomUUID().toString();
        artifacts.put(
                key(tenantId, artifactId),
                new Stored(Arrays.copyOf(payload, payload.length), digest,
                        metadata.contentType(), metadata.serializerVersion()));
        owners.computeIfAbsent(digestKey(tenantId, digest), k -> new HashSet<>()).add(metadata.ownerId());
        ContentReference ref =
                new ContentReference(tenantId, artifactId, digest, 1, metadata.serializerVersion());
        return new PutResult(ref, false);
    }

    @Override
    public synchronized byte[] get(ContentReference reference) {
        Stored stored = lookup(reference);
        if (!sha256(stored.payload()).equals(stored.digest())) {
            throw new ContentIntegrityException("digest mismatch for artifact " + reference.artifactId());
        }
        return Arrays.copyOf(stored.payload(), stored.payload().length);
    }

    @Override
    public synchronized boolean exists(ContentReference reference) {
        try {
            lookup(reference);
            return true;
        } catch (ContentNotFoundException e) {
            return false;
        }
    }

    @Override
    public synchronized void markForDeletion(ContentReference reference, String ownerId) {
        lookup(reference);
        Set<String> set = owners.get(digestKey(reference.tenantId(), reference.digest()));
        if (set != null) {
            set.remove(ownerId);
        }
    }

    @Override
    public synchronized void registerReference(String tenantId, String digest, String ownerId) {
        requireTenant(tenantId);
        Objects.requireNonNull(digest, "digest");
        owners.computeIfAbsent(digestKey(tenantId, digest), k -> new HashSet<>()).add(ownerId);
    }

    @Override
    public synchronized void releaseReference(String tenantId, String digest, String ownerId) {
        requireTenant(tenantId);
        Set<String> set = owners.get(digestKey(tenantId, digest));
        if (set != null) {
            set.remove(ownerId);
        }
    }

    @Override
    public synchronized void setLegalHold(ContentReference reference, boolean hold, String actor, String reason) {
        lookup(reference);
        if (reason == null || reason.isBlank()) {
            throw new ContentStoreException("reason is required for legal hold changes");
        }
        legalHold.put(key(reference.tenantId(), reference.artifactId()), hold);
    }

    @Override
    public synchronized void setRetention(ContentReference reference, int retentionDays) {
        lookup(reference);
        if (retentionDays <= 0) {
            throw new ContentStoreException("retentionDays must be positive");
        }
    }

    synchronized boolean isLegalHold(String tenantId, String artifactId) {
        return Boolean.TRUE.equals(legalHold.get(key(tenantId, artifactId)));
    }

    private Stored lookup(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        requireTenant(reference.tenantId());
        Stored stored = artifacts.get(key(reference.tenantId(), reference.artifactId()));
        if (stored == null || !stored.digest().equals(reference.digest())) {
            throw new ContentNotFoundException("artifact not found");
        }
        return stored;
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ContentStoreException("tenant_id is required");
        }
    }
}
