package org.synanton.contentcache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemContentStoreTest {

    @TempDir
    Path tempDir;

    private FileSystemContentStore store;

    @BeforeEach
    void setUp() {
        store = new FileSystemContentStore(tempDir.resolve("store"));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putGetRoundTrip() {
        PutResult res = store.put("acme", bytes("hello"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(res.dedupHit()).isFalse();
        assertThat(store.get(res.reference())).isEqualTo(bytes("hello"));
    }

    @Test
    void sameTenantDedupHits() {
        PutResult first = store.put("acme", bytes("dedup me"), ContentMetadata.of("text/plain", "owner-1"));
        PutResult second = store.put("acme", bytes("dedup me"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(second.dedupHit()).isTrue();
        assertThat(second.reference().artifactId()).isEqualTo(first.reference().artifactId());
    }

    @Test
    void sameBytesDifferentTenantsAreIsolated() {
        PutResult a = store.put("tenant-a", bytes("shared"), ContentMetadata.of("text/plain", "owner-1"));
        PutResult b = store.put("tenant-b", bytes("shared"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(b.reference().artifactId()).isNotEqualTo(a.reference().artifactId());
        assertThat(b.dedupHit()).isFalse();
    }

    @Test
    void crossTenantGetIsIndistinguishableNotFound() {
        PutResult res = store.put("tenant-a", bytes("secret"), ContentMetadata.of("text/plain", "owner-1"));
        ContentReference foreign = new ContentReference(
                "tenant-b", res.reference().artifactId(), res.reference().digest(), 1, 1);
        assertThatThrownBy(() -> store.get(foreign)).isInstanceOf(ContentNotFoundException.class);
        assertThat(store.exists(foreign)).isFalse();
    }

    @Test
    void survivesReopen() {
        PutResult res = store.put("acme", bytes("durable"), ContentMetadata.of("text/plain", "owner-1"));
        FileSystemContentStore reopened = new FileSystemContentStore(store.root());
        assertThat(reopened.get(res.reference())).isEqualTo(bytes("durable"));
        PutResult again = reopened.put("acme", bytes("durable"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(again.dedupHit()).isTrue();
        assertThat(again.reference().artifactId()).isEqualTo(res.reference().artifactId());
    }

    @Test
    void laysOutInspectableTree() throws Exception {
        PutResult res = store.put("acme", bytes("hello"), ContentMetadata.of("text/plain", "owner-1"));
        Path root = store.root();
        assertThat(root.resolve("blobs")).isDirectory();
        assertThat(root.resolve("artifacts").resolve("acme")
                .resolve(res.reference().artifactId() + ".json")).isRegularFile();
        try (var blobs = Files.walk(root.resolve("blobs"))) {
            assertThat(blobs.filter(Files::isRegularFile)).hasSize(1);
        }
        store.setLegalHold(res.reference(), true, "admin-1", "litigation hold");
        assertThat(root.resolve("audit.log")).isRegularFile();
        assertThat(Files.readString(root.resolve("audit.log"))).contains("legal_hold_place");
    }

    @Test
    void legalHoldRequiresReason() {
        PutResult res = store.put("acme", bytes("held"), ContentMetadata.of("text/plain", "owner-1"));
        assertThatThrownBy(() -> store.setLegalHold(res.reference(), true, "admin-1", ""))
                .isInstanceOf(ContentStoreException.class);
        store.setLegalHold(res.reference(), true, "admin-1", "litigation hold");
        store.setLegalHold(res.reference(), false, "admin-1", "matter closed");
    }

    @Test
    void registerReleaseReferenceLifecycle() {
        PutResult res = store.put("acme", bytes("registry"), ContentMetadata.of("text/plain", "owner-1"));
        store.registerReference("acme", res.reference().digest(), "owner-2");
        store.releaseReference("acme", res.reference().digest(), "owner-2");
        store.markForDeletion(res.reference(), "owner-1");
        assertThat(store.exists(res.reference())).isTrue();
    }

    @Test
    void tamperedBlobFailsClosed() throws Exception {
        PutResult res = store.put("acme", bytes("original"), ContentMetadata.of("text/plain", "owner-1"));
        Path root = store.root();
        try (var blobs = Files.walk(root.resolve("blobs"))) {
            Path blob = blobs.filter(Files::isRegularFile).findFirst().orElseThrow();
            Files.write(blob, bytes("tampered"));
        }
        assertThatThrownBy(() -> store.get(res.reference()))
                .isInstanceOf(ContentIntegrityException.class);
    }

    @Test
    void rejectsInvalidTenantAndUnknownArtifact() {
        assertThatThrownBy(() -> store.put("../evil", bytes("x"), ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
        assertThatThrownBy(() -> store.put("", bytes("x"), ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
        ContentReference bogus =
                new ContentReference("acme", "not-a-uuid", "sha256:abc123", 1, 1);
        assertThatThrownBy(() -> store.get(bogus)).isInstanceOf(ContentNotFoundException.class);
        assertThat(store.exists(bogus)).isFalse();
    }

    @Test
    void createTempStoreIsUsable() {
        FileSystemContentStore tmp = FileSystemContentStore.createTemp();
        PutResult res = tmp.put("acme", bytes("ephemeral"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(tmp.get(res.reference())).isEqualTo(bytes("ephemeral"));
    }
}
