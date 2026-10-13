package org.synanton.contentcache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Filesystem {@link ContentStore} for testing without a deployed Content Service.
 *
 * <p>Use when persistence across restarts (or payloads too large for heap) matters
 * but no server is available: same-machine durable storage behind the same port
 * as {@link InMemoryContentStore} and the JDBC adapter, so contract tests run
 * unchanged. Not for production: methods are process-synchronized only, there is
 * no cross-process locking, and reads never leave the local disk.
 *
 * <p>On-disk layout under {@code <root>/}, chosen for inspectability with zero
 * dependencies (no Parquet/Avro — disproportionate for a test fake; raw bytes
 * plus JSON sidecars are readable with {@code cat}):
 *
 * <pre>
 * blobs/&lt;aa&gt;/&lt;bb&gt;/&lt;rest&gt;          raw payload bytes, content-addressed by digest hex
 * by-digest/&lt;tenant&gt;/&lt;hex&gt;              artifact-id pointer for (tenant, digest) dedup
 * artifacts/&lt;tenant&gt;/&lt;uuid&gt;.json        digest, generation, type, size, createdAt
 * refs/&lt;tenant&gt;/&lt;hex&gt;/&lt;owner&gt;.json  owner registrations (owner is url-safe base64)
 * retention/&lt;tenant&gt;/&lt;uuid&gt;/&lt;owner&gt;.json  retention window + legal hold per owner
 * audit.log                               append-only JSONL of legal-hold changes
 * </pre>
 *
 * <p>Blobs shard by digest prefix (git-style) so directories stay small at
 * volume. Every file write goes through a temp file plus atomic move; tenant
 * ids are restricted to {@code [A-Za-z0-9_-]{1,64}} and artifact ids to UUIDs
 * so path segments can never escape the root.
 */
public final class FileSystemContentStore implements ContentStore {

    private static final String TENANT_PATTERN = "[A-Za-z0-9_-]{1,64}";
    private static final String HEX_PATTERN = "[0-9a-f]+";

    private final Path root;
    private final int defaultRetentionDays;

    /**
     * Opens (creating if needed) a store rooted at {@code root}.
     *
     * @param root directory holding the store layout, must not be null
     */
    public FileSystemContentStore(Path root) {
        this(root, 90);
    }

    /**
     * Opens (creating if needed) a store rooted at {@code root}.
     *
     * @param root directory holding the store layout, must not be null
     * @param defaultRetentionDays retention applied when metadata carries none, must be positive
     */
    public FileSystemContentStore(Path root, int defaultRetentionDays) {
        this.root = Objects.requireNonNull(root, "root");
        if (defaultRetentionDays <= 0) {
            throw new IllegalArgumentException("defaultRetentionDays must be positive");
        }
        this.defaultRetentionDays = defaultRetentionDays;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ContentStoreException("cannot initialize store root " + root, e);
        }
    }

    /**
     * Creates a store in a fresh temp directory (deleted on JVM exit when empty).
     * Handy for tests; prefer an explicit root for anything that must survive.
     */
    public static FileSystemContentStore createTemp() {
        try {
            return new FileSystemContentStore(Files.createTempDirectory("content-cache-"));
        } catch (IOException e) {
            throw new ContentStoreException("cannot create temp store root", e);
        }
    }

    /** Root directory of the store layout. */
    public Path root() {
        return root;
    }

    @Override
    public synchronized PutResult put(String tenantId, byte[] payload, ContentMetadata metadata) {
        requireValidTenant(tenantId);
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(metadata, "metadata");
        if (payload.length == 0) {
            throw new ContentStoreException("payload is empty");
        }
        String digest = InMemoryContentStore.sha256(payload);
        String hex = hexOf(digest);
        String existing = readPointer(tenantId, hex);
        if (existing != null) {
            upsertReference(tenantId, digest, metadata.ownerId());
            ContentReference ref = new ContentReference(
                    tenantId, existing, digest, 1, metadata.serializerVersion());
            checkLength(ref);
            return new PutResult(ref, true);
        }
        String artifactId = UUID.randomUUID().toString();
        writeBlob(hex, payload);
        writePointer(tenantId, hex, artifactId);
        writeArtifact(tenantId, artifactId, digest, metadata, payload.length);
        writeRetention(tenantId, artifactId, metadata.ownerId(),
                metadata.retentionDays() > 0 ? metadata.retentionDays() : defaultRetentionDays, false);
        upsertReference(tenantId, digest, metadata.ownerId());
        ContentReference ref = new ContentReference(
                tenantId, artifactId, digest, 1, metadata.serializerVersion());
        checkLength(ref);
        return new PutResult(ref, false);
    }

    @Override
    public synchronized byte[] get(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        Map<String, String> artifact = readArtifact(reference.tenantId(), reference.artifactId());
        if (artifact == null || !reference.digest().equals(artifact.get("digest"))) {
            throw new ContentNotFoundException("artifact not found");
        }
        byte[] payload = readBlob(hexOf(reference.digest()));
        if (payload == null || !InMemoryContentStore.sha256(payload).equals(reference.digest())) {
            throw new ContentIntegrityException("digest mismatch for artifact " + reference.artifactId());
        }
        return Arrays.copyOf(payload, payload.length);
    }

    @Override
    public synchronized boolean exists(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        return readArtifact(reference.tenantId(), reference.artifactId()) != null;
    }

    @Override
    public synchronized void markForDeletion(ContentReference reference, String ownerId) {
        Objects.requireNonNull(reference, "reference");
        Map<String, String> artifact = readArtifact(reference.tenantId(), reference.artifactId());
        if (artifact == null) {
            throw new ContentNotFoundException("artifact not found");
        }
        releaseReference(reference.tenantId(), artifact.get("digest"), ownerId);
    }

    @Override
    public synchronized void registerReference(String tenantId, String digest, String ownerId) {
        requireValidTenant(tenantId);
        Objects.requireNonNull(digest, "digest");
        upsertReference(tenantId, digest, ownerId);
    }

    @Override
    public synchronized void releaseReference(String tenantId, String digest, String ownerId) {
        requireValidTenant(tenantId);
        Objects.requireNonNull(digest, "digest");
        Path file = refFile(tenantId, hexOf(digest), ownerId);
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, String> fields = readJson(file);
        if (fields == null) {
            return;
        }
        fields.put("releasedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        writeJson(file, fields);
    }

    @Override
    public synchronized void setLegalHold(
            ContentReference reference, boolean hold, String actor, String reason) {
        Objects.requireNonNull(reference, "reference");
        if (reason == null || reason.isBlank()) {
            throw new ContentStoreException("reason is required for legal hold changes");
        }
        Path dir = retentionDir(reference.tenantId(), reference.artifactId());
        if (!Files.isDirectory(dir) || isEmptyDir(dir)) {
            throw new ContentNotFoundException("artifact not found");
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                Map<String, String> fields = readJson(file);
                if (fields == null) {
                    continue;
                }
                fields.put("legalHold", Boolean.toString(hold));
                writeJson(file, fields);
            }
        } catch (IOException e) {
            throw new ContentStoreException("setLegalHold failed", e);
        }
        appendAudit(actor, hold ? "legal_hold_place" : "legal_hold_release",
                reference.tenantId(), reference.artifactId(), "artifact", reason, "", "");
    }

    @Override
    public synchronized void setRetention(ContentReference reference, int retentionDays) {
        Objects.requireNonNull(reference, "reference");
        if (retentionDays <= 0) {
            throw new ContentStoreException("retentionDays must be positive");
        }
        Path dir = retentionDir(reference.tenantId(), reference.artifactId());
        if (!Files.isDirectory(dir) || isEmptyDir(dir)) {
            throw new ContentNotFoundException("artifact not found");
        }
        OffsetDateTime expiresAt =
                OffsetDateTime.now(ZoneOffset.UTC).plus(retentionDays, ChronoUnit.DAYS);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                Map<String, String> fields = readJson(file);
                if (fields == null) {
                    continue;
                }
                fields.put("retentionDays", Integer.toString(retentionDays));
                fields.put("expiresAt", expiresAt.toString());
                writeJson(file, fields);
            }
        } catch (IOException e) {
            throw new ContentStoreException("setRetention failed", e);
        }
    }

    private void upsertReference(String tenantId, String digest, String ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        Path file = refFile(tenantId, hexOf(digest), ownerId);
        Map<String, String> fields = readJson(file);
        if (fields == null) {
            fields = new LinkedHashMap<>();
            fields.put("ownerId", ownerId);
            fields.put("ownerType", "commitix_intent");
            fields.put("createdAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        }
        fields.remove("releasedAt");
        writeJson(file, fields);
    }

    private void writeBlob(String hex, byte[] payload) {
        writeBytes(blobFile(hex), payload);
    }

    private byte[] readBlob(String hex) {
        Path file = hex == null ? null : blobFile(hex);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ContentStoreException("cannot read blob", e);
        }
    }

    private void writePointer(String tenantId, String hex, String artifactId) {
        writeBytes(pointerFile(tenantId, hex), artifactId.getBytes(StandardCharsets.UTF_8));
    }

    private String readPointer(String tenantId, String hex) {
        if (hex == null) {
            return null;
        }
        Path file = pointerFile(tenantId, hex);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new ContentStoreException("cannot read dedup pointer", e);
        }
    }

    private void writeArtifact(
            String tenantId, String artifactId, String digest, ContentMetadata metadata, long size) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("digest", digest);
        fields.put("generation", "1");
        fields.put("serializerVersion", Integer.toString(metadata.serializerVersion()));
        fields.put("contentType", metadata.contentType() == null ? "" : metadata.contentType());
        fields.put("sizeBytes", Long.toString(size));
        fields.put("storageTier", "filesystem");
        fields.put("createdAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        writeJson(artifactFile(tenantId, artifactId), fields);
    }

    private Map<String, String> readArtifact(String tenantId, String artifactId) {
        if (!isValidTenant(tenantId) || !isUuid(artifactId)) {
            return null;
        }
        return readJson(artifactFile(tenantId, artifactId));
    }

    private void writeRetention(
            String tenantId, String artifactId, String ownerId, int retentionDays, boolean legalHold) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ownerId", ownerId);
        fields.put("retentionDays", Integer.toString(retentionDays));
        fields.put("expiresAt", OffsetDateTime.now(ZoneOffset.UTC)
                .plus(retentionDays, ChronoUnit.DAYS).toString());
        fields.put("legalHold", Boolean.toString(legalHold));
        writeJson(retentionFile(tenantId, artifactId, ownerId), fields);
    }

    private void appendAudit(String actor, String action, String tenantId,
            String artifactId, String scope, String reason, String before, String after) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC).toString());
        fields.put("actor", actor);
        fields.put("action", action);
        fields.put("tenantId", tenantId);
        fields.put("artifactId", artifactId);
        fields.put("scope", scope);
        fields.put("reason", reason);
        fields.put("beforeState", before);
        fields.put("afterState", after);
        String line = JsonObjects.write(fields) + "\n";
        try {
            Files.writeString(root.resolve("audit.log"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new ContentStoreException("cannot append audit log", e);
        }
    }

    private Map<String, String> readJson(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JsonObjects.read(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ContentStoreException("cannot read " + root.relativize(file), e);
        }
    }

    private void writeJson(Path file, Map<String, String> fields) {
        writeBytes(file, JsonObjects.write(fields).getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), ".tmp-", ".part");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort cleanup of the staging file
                }
                throw e;
            }
        } catch (IOException e) {
            throw new ContentStoreException("cannot write " + root.relativize(file), e);
        }
    }

    private Path blobFile(String hex) {
        return root.resolve("blobs").resolve(hex.substring(0, 2))
                .resolve(hex.substring(2, 4)).resolve(hex.substring(4));
    }

    private Path pointerFile(String tenantId, String hex) {
        return root.resolve("by-digest").resolve(tenantId).resolve(hex);
    }

    private Path artifactFile(String tenantId, String artifactId) {
        return root.resolve("artifacts").resolve(tenantId).resolve(artifactId + ".json");
    }

    private Path refFile(String tenantId, String hex, String ownerId) {
        return root.resolve("refs").resolve(tenantId).resolve(hex)
                .resolve(ownerFile(ownerId) + ".json");
    }

    private Path retentionDir(String tenantId, String artifactId) {
        return root.resolve("retention").resolve(tenantId).resolve(artifactId);
    }

    private Path retentionFile(String tenantId, String artifactId, String ownerId) {
        return retentionDir(tenantId, artifactId).resolve(ownerFile(ownerId) + ".json");
    }

    private static String ownerFile(String ownerId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ownerId.getBytes(StandardCharsets.UTF_8));
    }

    private static String hexOf(String digest) {
        if (digest == null) {
            return null;
        }
        int colon = digest.indexOf(':');
        String hex = colon >= 0 ? digest.substring(colon + 1) : digest;
        if (hex.length() < 8 || !hex.matches(HEX_PATTERN)) {
            return null;
        }
        return hex;
    }

    private static boolean isValidTenant(String tenantId) {
        return tenantId != null && tenantId.matches(TENANT_PATTERN);
    }

    private static void requireValidTenant(String tenantId) {
        if (!isValidTenant(tenantId)) {
            throw new ContentStoreException("tenant_id is required");
        }
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isEmptyDir(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            throw new ContentStoreException("cannot list " + dir, e);
        }
    }

    private static void checkLength(ContentReference ref) {
        if (ref.encode().length() > ContentReference.MAX_LENGTH) {
            throw new ContentStoreException("encoded reference exceeds 512 characters");
        }
    }
}
