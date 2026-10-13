package org.synanton.contentcache.grpc;

import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.synanton.content.v1.ContentServiceGrpc;
import io.synanton.content.v1.GetRequest;
import io.synanton.content.v1.HeadRequest;
import io.synanton.content.v1.PutRequest;
import org.synanton.contentcache.ContentIntegrityException;
import org.synanton.contentcache.ContentMetadata;
import org.synanton.contentcache.ContentNotFoundException;
import org.synanton.contentcache.ContentReference;
import org.synanton.contentcache.ContentStore;
import org.synanton.contentcache.ContentStoreException;
import org.synanton.contentcache.PutResult;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@link ContentStore} over the Content Service gRPC API
 * ({@code content.v1.ContentService}).
 *
 * <p>Every RPC carries an explicit deadline (default 5s per proposal §3.3);
 * retries, if any, are bounded by the caller's deadline. Reads verify the
 * SHA-256 digest and fail closed on mismatch. Transport is plaintext by
 * default; front with mTLS termination before exposing it beyond loopback.
 *
 * <p>Lifecycle operations beyond put/get/exists (reference register and
 * release, legal hold, retention) have no RPC surface yet — the Reference
 * Registry API is still proposed (Appendix B of the integration proposal) —
 * so they fail explicitly rather than silently no-op.
 */
public final class GrpcContentStore implements ContentStore {

    private static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(5);

    private final ContentServiceGrpc.ContentServiceBlockingStub stub;
    private final ManagedChannel ownedChannel;
    private final Duration deadline;

    private GrpcContentStore(ContentServiceGrpc.ContentServiceBlockingStub stub,
            ManagedChannel ownedChannel, Duration deadline) {
        this.stub = Objects.requireNonNull(stub, "stub");
        this.ownedChannel = ownedChannel;
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    /**
     * Creates a client over the given channel. The caller owns the channel
     * lifecycle; {@link #close()} is a no-op for channel-backed instances.
     */
    public static GrpcContentStore over(Channel channel) {
        return over(channel, DEFAULT_DEADLINE);
    }

    /**
     * Creates a client over the given channel with an explicit RPC deadline.
     */
    public static GrpcContentStore over(Channel channel, Duration deadline) {
        return new GrpcContentStore(
                ContentServiceGrpc.newBlockingStub(channel), null, deadline);
    }

    /**
     * Creates a plaintext client to {@code host:port} and owns its channel;
     * call {@link #close()} to release it.
     */
    public static GrpcContentStore plaintext(String host, int port) {
        return plaintext(host, port, DEFAULT_DEADLINE);
    }

    /**
     * Creates a plaintext client with an explicit RPC deadline.
     */
    public static GrpcContentStore plaintext(String host, int port, Duration deadline) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        return new GrpcContentStore(
                ContentServiceGrpc.newBlockingStub(channel), channel, deadline);
    }

    /** Releases the owned channel, if any; no-op for channel-backed instances. */
    public void close() {
        if (ownedChannel != null && !ownedChannel.isShutdown()) {
            ownedChannel.shutdown();
        }
    }

    @Override
    public PutResult put(String tenantId, byte[] payload, ContentMetadata metadata) {
        requireTenant(tenantId);
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(metadata, "metadata");
        if (payload.length == 0) {
            throw new ContentStoreException("payload is empty");
        }
        PutRequest request = PutRequest.newBuilder()
                .setTenantId(tenantId)
                .setPayload(ByteString.copyFrom(payload))
                .setContentType(nullToEmpty(metadata.contentType()))
                .setOwnerId(metadata.ownerId())
                .setRetentionDays(metadata.retentionDays())
                .setSerializerVersion(metadata.serializerVersion())
                .build();
        try {
            var response = stubWithDeadline().put(request);
            return new PutResult(
                    new ContentReference(tenantId, response.getArtifactId(),
                            response.getDigest(), 1, metadata.serializerVersion()),
                    response.getDedupHit());
        } catch (StatusRuntimeException e) {
            throw translate("put", e);
        }
    }

    @Override
    public byte[] get(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        GetRequest request = GetRequest.newBuilder()
                .setTenantId(reference.tenantId())
                .setArtifactId(reference.artifactId())
                .build();
        try {
            var response = stubWithDeadline().get(request);
            byte[] bytes = response.getPayload().toByteArray();
            if (!sha256(bytes).equals(response.getDigest())
                    || !response.getDigest().equals(reference.digest())) {
                throw new ContentIntegrityException(
                        "digest mismatch for artifact " + reference.artifactId());
            }
            return Arrays.copyOf(bytes, bytes.length);
        } catch (StatusRuntimeException e) {
            throw translate("get", e);
        }
    }

    @Override
    public boolean exists(ContentReference reference) {
        Objects.requireNonNull(reference, "reference");
        HeadRequest request = HeadRequest.newBuilder()
                .setTenantId(reference.tenantId())
                .setArtifactId(reference.artifactId())
                .build();
        try {
            return stubWithDeadline().head(request).getExists();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return false;
            }
            throw translate("exists", e);
        }
    }

    @Override
    public void markForDeletion(ContentReference reference, String ownerId) {
        throw new ContentStoreException(
                "markForDeletion is not supported over the gRPC transport:"
                        + " the Reference Registry API is still proposed, not implemented");
    }

    @Override
    public void registerReference(String tenantId, String digest, String ownerId) {
        throw new ContentStoreException(
                "registerReference is not supported over the gRPC transport:"
                        + " the Reference Registry API is still proposed, not implemented");
    }

    @Override
    public void releaseReference(String tenantId, String digest, String ownerId) {
        throw new ContentStoreException(
                "releaseReference is not supported over the gRPC transport:"
                        + " the Reference Registry API is still proposed, not implemented");
    }

    @Override
    public void setLegalHold(
            ContentReference reference, boolean hold, String actor, String reason) {
        throw new ContentStoreException(
                "setLegalHold is not supported over the gRPC transport:"
                        + " lifecycle RPCs are not in the Phase 1 API surface");
    }

    @Override
    public void setRetention(ContentReference reference, int retentionDays) {
        throw new ContentStoreException(
                "setRetention is not supported over the gRPC transport:"
                        + " lifecycle RPCs are not in the Phase 1 API surface");
    }

    private ContentServiceGrpc.ContentServiceBlockingStub stubWithDeadline() {
        return stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static ContentStoreException translate(String operation, StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        if (code == Status.Code.NOT_FOUND) {
            return new ContentNotFoundException("artifact not found");
        }
        if (code == Status.Code.DEADLINE_EXCEEDED || code == Status.Code.UNAVAILABLE) {
            return new ContentStoreException(
                    "Content Service " + operation + " unavailable: " + e.getStatus(), e);
        }
        return new ContentStoreException(
                "Content Service " + operation + " failed: " + e.getStatus(), e);
    }

    static String sha256(byte[] payload) {
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

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ContentStoreException("tenant_id is required");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
