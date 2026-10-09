package org.synanton.synquest.qdrant;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.GenerationId;
import org.synanton.storage.contract.StorageErrorKind;
import org.synanton.storage.contract.StorageException;
import org.synanton.synquest.api.Projection;
import org.synanton.synquest.api.SearchCapabilities;
import org.synanton.synquest.api.SearchHit;
import org.synanton.storage.contract.SecurityContext;
import org.synanton.synquest.api.SynquestIndexWriter;
import org.synanton.synquest.api.VectorProjection;
import org.synanton.synquest.api.VectorRetriever;
import org.synanton.synquest.api.VectorSearchRequest;
import org.synanton.synquest.api.VectorSearchResult;

/**
 * Qdrant vector adapter (SYN-VECTOR-001 B3.2): {@link VectorRetriever} plus the
 * {@link SynquestIndexWriter} write path against one Qdrant collection.
 * Text/metadata stay in the metadata store — points carry identity
 * (chunk_id, doc_id, tenant) plus the vector, so hits return empty text and
 * recall is computed on chunk IDs. Point IDs are deterministic
 * UUIDs derived from chunk IDs, so deletes need no separate mapping.
 *
 * <p>TLS is the caller's concern: construct the {@code QdrantClient} with TLS
 * for secured deployments (tests use plaintext against the local container).
 */
public final class QdrantVectorRetriever implements VectorRetriever, SynquestIndexWriter {

    static final String PAYLOAD_CHUNK_ID = "chunk_id";
    static final String PAYLOAD_DOC_ID = "doc_id";
    static final String PAYLOAD_TENANT = "tenant";

    private final QdrantClient client;
    private final String collection;
    private final int dim;
    private final AtomicReference<GenerationId> activeGeneration = new AtomicReference<>();

    public QdrantVectorRetriever(QdrantClient client, String collection, int dim) {
        this.client = Objects.requireNonNull(client, "client");
        this.collection = Objects.requireNonNull(collection, "collection");
        if (dim < 1) {
            throw new IllegalArgumentException("dim must be >= 1");
        }
        this.dim = dim;
        ensureCollection();
    }

    private void ensureCollection() {
        try {
            client.getCollectionInfoAsync(collection).get(30, TimeUnit.SECONDS).getConfig();
            return;
        } catch (Exception expected) {
            // Absent — create below.
        }
        try {
            client.createCollectionAsync(
                            collection,
                            Collections.VectorParams.newBuilder()
                                    .setSize(dim)
                                    .setDistance(Collections.Distance.Cosine)
                                    .build())
                    .get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new StorageException(
                    StorageErrorKind.UNAVAILABLE, "qdrant createCollection failed: " + message(e));
        }
    }

    @Override
    public CompletionStage<VectorSearchResult> search(SecurityContext context, VectorSearchRequest request) {
        String tenant = request.eligibility().tenantScope().tenantId();
        List<Float> query = new ArrayList<>(request.queryEmbedding().length);
        for (float value : request.queryEmbedding()) {
            query.add(value);
        }
        Points.SearchPoints search = Points.SearchPoints.newBuilder()
                .setCollectionName(collection)
                .addAllVector(query)
                .setLimit(request.topK())
                .setFilter(tenantFilter(tenant))
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                .build();
        return await(client.searchAsync(search)).thenApply(points -> {
            List<SearchHit> hits = new ArrayList<>(points.size());
            for (Points.ScoredPoint point : points) {
                Map<String, JsonWithInt.Value> payload = point.getPayloadMap();
                hits.add(
                        new SearchHit(
                                ChunkId.of(string(payload, PAYLOAD_CHUNK_ID)),
                                new DocumentId(string(payload, PAYLOAD_DOC_ID)),
                                point.getScore(),
                                "",
                                Map.of()));
            }
            return new VectorSearchResult(hits, hits.size());
        });
    }

    @Override
    public SearchCapabilities capabilities() {
        return new SearchCapabilities(false, true, false, false, false, false, false, false);
    }

    @Override
    public CompletionStage<Void> upsert(List<? extends Projection> projections) {
        List<VectorProjection> vectors = new ArrayList<>(projections.size());
        for (Projection projection : projections) {
            if (!(projection instanceof VectorProjection vector)) {
                return CompletableFuture.failedFuture(
                        new StorageException(
                                StorageErrorKind.UNSUPPORTED,
                                "unsupported projection type for upsert: "
                                        + projection.getClass().getSimpleName()));
            }
            vectors.add(vector);
        }
        adoptGeneration(vectors);
        List<Points.PointStruct> points = new ArrayList<>(vectors.size());
        for (VectorProjection vector : vectors) {
            if (vector.embedding().length != dim) {
                return CompletableFuture.failedFuture(
                        new StorageException(
                                StorageErrorKind.UNSUPPORTED,
                                "embedding dim " + vector.embedding().length
                                        + " does not match collection dim " + dim));
            }
            List<Float> values = new ArrayList<>(vector.embedding().length);
            for (float value : vector.embedding()) {
                values.add(value);
            }
            points.add(
                    Points.PointStruct.newBuilder()
                            .setId(Common.PointId.newBuilder()
                                    .setUuid(pointUuid(vector.chunkId().value()).toString())
                                    .build())
                            .setVectors(VectorsFactory.vectors(values))
                            .putPayload(PAYLOAD_CHUNK_ID, stringValue(vector.chunkId().value()))
                            .putPayload(PAYLOAD_DOC_ID, stringValue(vector.documentId().value()))
                            .putPayload(PAYLOAD_TENANT, stringValue(vector.tenantId()))
                            .build());
        }
        return await(client.upsertAsync(collection, points)).thenApply(ignored -> null);
    }

    @Override
    public CompletionStage<Void> delete(GenerationId generationId, Collection<ChunkId> ids) {
        GenerationId active = activeGeneration.get();
        if (active != null && !generationId.equals(active)) {
            return CompletableFuture.failedFuture(
                    new StorageException(
                            StorageErrorKind.CONFLICT,
                            "CONFLICT: stale generation '" + generationId.value() + "'"));
        }
        List<Common.PointId> points = new ArrayList<>();
        for (ChunkId id : ids) {
            points.add(Common.PointId.newBuilder()
                    .setUuid(pointUuid(id.value()).toString())
                    .build());
        }
        return await(client.deleteAsync(collection, points)).thenApply(ignored -> null);
    }

    private void adoptGeneration(List<VectorProjection> vectors) {
        for (VectorProjection vector : vectors) {
            GenerationId active = activeGeneration.get();
            if (active == null) {
                activeGeneration.compareAndSet(null, vector.generationId());
                active = activeGeneration.get();
            }
            if (!vector.generationId().equals(active)) {
                throw new StorageException(
                        StorageErrorKind.CONFLICT,
                        "CONFLICT: stale generation '" + vector.generationId().value() + "'");
            }
        }
    }

    private static Common.Filter tenantFilter(String tenant) {
        return Common.Filter.newBuilder()
                .addMust(Common.Condition.newBuilder()
                        .setField(Common.FieldCondition.newBuilder()
                                .setKey(PAYLOAD_TENANT)
                                .setMatch(Common.Match.newBuilder().setKeyword(tenant).build())
                                .build())
                        .build())
                .build();
    }

    private static UUID pointUuid(String chunkId) {
        return UUID.nameUUIDFromBytes(
                chunkId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static JsonWithInt.Value stringValue(String value) {
        return JsonWithInt.Value.newBuilder().setStringValue(value).build();
    }

    private static String string(Map<String, JsonWithInt.Value> payload, String key) {
        JsonWithInt.Value value = payload.get(key);
        if (value == null) {
            return "";
        }
        try {
            return value.getStringValue();
        } catch (Exception e) {
            return "";
        }
    }

    private static <T> CompletionStage<T> await(
            com.google.common.util.concurrent.ListenableFuture<T> future) {
        CompletableFuture<T> out = new CompletableFuture<>();
        Futures.addCallback(
                future,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(T result) {
                        out.complete(result);
                    }

                    @Override
                    public void onFailure(Throwable failure) {
                        out.completeExceptionally(
                                new StorageException(
                                        StorageErrorKind.UNAVAILABLE,
                                        "qdrant failure: " + message(failure)));
                    }
                },
                MoreExecutors.directExecutor());
        return out;
    }

    private static String message(Throwable failure) {
        return failure == null ? "unknown" : String.valueOf(failure.getMessage());
    }
}
