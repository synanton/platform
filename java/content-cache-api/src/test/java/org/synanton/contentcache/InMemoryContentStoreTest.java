package org.synanton.contentcache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryContentStoreTest {

    private InMemoryContentStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryContentStore();
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
    void legalHoldRequiresReason() {
        PutResult res = store.put("acme", bytes("held"), ContentMetadata.of("text/plain", "owner-1"));
        assertThatThrownBy(() -> store.setLegalHold(res.reference(), true, "admin-1", ""))
                .isInstanceOf(ContentStoreException.class);
        store.setLegalHold(res.reference(), true, "admin-1", "litigation hold");
        assertThat(store.isLegalHold("acme", res.reference().artifactId())).isTrue();
    }

    @Test
    void rejectsEmptyPayloadAndMissingTenant() {
        assertThatThrownBy(() -> store.put("acme", new byte[0], ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
        assertThatThrownBy(() -> store.put("", bytes("x"), ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
    }
}
