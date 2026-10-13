package org.synanton.contentcache.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.synanton.content.v1.CapabilitiesRequest;
import io.synanton.content.v1.CapabilitiesResponse;
import io.synanton.content.v1.ContentServiceGrpc;
import io.synanton.content.v1.GetRequest;
import io.synanton.content.v1.GetResponse;
import io.synanton.content.v1.HeadRequest;
import io.synanton.content.v1.HeadResponse;
import io.synanton.content.v1.PutRequest;
import io.synanton.content.v1.PutResponse;
import org.synanton.contentcache.ContentIntegrityException;
import org.synanton.contentcache.ContentMetadata;
import org.synanton.contentcache.ContentNotFoundException;
import org.synanton.contentcache.ContentReference;
import org.synanton.contentcache.ContentStore;
import org.synanton.contentcache.ContentStoreException;
import org.synanton.contentcache.InMemoryContentStore;
import org.synanton.contentcache.PutResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link GrpcContentStore} against an in-process fake
 * that delegates to {@link InMemoryContentStore}: the transport mapping
 * (codes, deadlines, digest binding) is what's under test, not storage.
 */
class GrpcContentStoreTest {

    static final class FakeService extends ContentServiceGrpc.ContentServiceImplBase {
        final ContentStore store = new InMemoryContentStore();
        final Map<String, byte[]> overrides = new ConcurrentHashMap<>();

        @Override
        public void put(PutRequest request, StreamObserver<PutResponse> observer) {
            if (request.getTenantId().isBlank() || request.getPayload().isEmpty()) {
                observer.onError(Status.INVALID_ARGUMENT.asRuntimeException());
                return;
            }
            try {
                PutResult res = store.put(request.getTenantId(), request.getPayload().toByteArray(),
                        new ContentMetadata(request.getContentType(), request.getOwnerId(),
                                Math.max(request.getRetentionDays(), 1),
                                Math.max(request.getSerializerVersion(), 1)));
                observer.onNext(PutResponse.newBuilder()
                        .setReference(res.reference().encode())
                        .setArtifactId(res.reference().artifactId())
                        .setDigest(res.reference().digest())
                        .setDedupHit(res.dedupHit())
                        .setStorageTier("inline")
                        .build());
                observer.onCompleted();
            } catch (ContentStoreException e) {
                observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void get(GetRequest request, StreamObserver<GetResponse> observer) {
            try {
                String digest = ((InMemoryContentStore) store)
                        .digestOf(request.getTenantId(), request.getArtifactId())
                        .orElseThrow(() -> new ContentNotFoundException("artifact not found"));
                byte[] override = overrides.get(request.getTenantId() + "\0" + request.getArtifactId());
                byte[] payload = override != null
                        ? Arrays.copyOf(override, override.length)
                        : store.get(new ContentReference(request.getTenantId(),
                                request.getArtifactId(), digest, 1, 1));
                observer.onNext(GetResponse.newBuilder()
                        .setPayload(ByteString.copyFrom(payload))
                        .setDigest(digest)
                        .setArtifactId(request.getArtifactId())
                        .build());
                observer.onCompleted();
            } catch (ContentNotFoundException e) {
                observer.onError(Status.NOT_FOUND.asRuntimeException());
            } catch (ContentStoreException e) {
                observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
            }
        }

        @Override
        public void head(HeadRequest request, StreamObserver<HeadResponse> observer) {
            if (((InMemoryContentStore) store)
                    .digestOf(request.getTenantId(), request.getArtifactId()).isEmpty()) {
                observer.onError(Status.NOT_FOUND.asRuntimeException());
                return;
            }
            observer.onNext(HeadResponse.newBuilder()
                    .setExists(true)
                    .setArtifactId(request.getArtifactId())
                    .build());
            observer.onCompleted();
        }

        @Override
        public void capabilities(CapabilitiesRequest request, StreamObserver<CapabilitiesResponse> observer) {
            observer.onNext(CapabilitiesResponse.newBuilder()
                    .setConformanceLevel("CORE")
                    .build());
            observer.onCompleted();
        }

        void tamper(String tenantId, String artifactId, byte[] bytes) {
            overrides.put(tenantId + "\0" + artifactId, Arrays.copyOf(bytes, bytes.length));
        }
    }

    private io.grpc.Server server;
    private GrpcContentStore store;
    private final FakeService fake = new FakeService();

    @BeforeEach
    void setUp() throws IOException {
        String name = "content-it-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(name).addService(fake).build().start();
        store = GrpcContentStore.over(InProcessChannelBuilder.forName(name).build());
    }

    @AfterEach
    void tearDown() {
        server.shutdownNow();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void putGetRoundTrip() {
        PutResult res = store.put("acme", bytes("hello"), ContentMetadata.of("text/plain", "owner-1"));
        assertThat(res.dedupHit()).isFalse();
        assertThat(store.get(res.reference())).isEqualTo(bytes("hello"));
        assertThat(store.exists(res.reference())).isTrue();
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
    void corruptRemoteBytesFailClosed() {
        PutResult res = store.put("acme", bytes("original"), ContentMetadata.of("text/plain", "owner-1"));
        fake.tamper("acme", res.reference().artifactId(), bytes("tampered"));
        assertThatThrownBy(() -> store.get(res.reference()))
                .isInstanceOf(ContentIntegrityException.class);
    }

    @Test
    void rejectsEmptyPayloadAndMissingTenant() {
        assertThatThrownBy(() -> store.put("acme", new byte[0], ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
        assertThatThrownBy(() -> store.put("", bytes("x"), ContentMetadata.of("text/plain", "o")))
                .isInstanceOf(ContentStoreException.class);
    }

    @Test
    void lifecycleOperationsFailExplicitly() {
        PutResult res = store.put("acme", bytes("lifecycle"), ContentMetadata.of("text/plain", "owner-1"));
        assertThatThrownBy(() -> store.markForDeletion(res.reference(), "owner-1"))
                .isInstanceOf(ContentStoreException.class)
                .hasMessageContaining("not supported");
        assertThatThrownBy(() -> store.registerReference("acme", res.reference().digest(), "owner-2"))
                .isInstanceOf(ContentStoreException.class);
        assertThatThrownBy(() -> store.releaseReference("acme", res.reference().digest(), "owner-1"))
                .isInstanceOf(ContentStoreException.class);
        assertThatThrownBy(() -> store.setLegalHold(res.reference(), true, "admin-1", "reason"))
                .isInstanceOf(ContentStoreException.class);
        assertThatThrownBy(() -> store.setRetention(res.reference(), 30))
                .isInstanceOf(ContentStoreException.class);
    }
}
