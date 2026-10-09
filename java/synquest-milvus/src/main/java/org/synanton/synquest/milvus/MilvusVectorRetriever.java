package org.synanton.synquest.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.synanton.storage.contract.ChunkId;
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
 * Milvus vector adapter (SYN-VECTOR-001 B3.1): {@link VectorRetriever} plus the
 * {@link SynquestIndexWriter} write path against one Milvus collection.
 * Text/metadata stay in the metadata store — Milvus rows carry identity
 * (chunk_id, tenant, doc_id) plus the vector, so hits return empty text and
 * recall is computed on chunk IDs.
 */
public final class MilvusVectorRetriever implements VectorRetriever, SynquestIndexWriter {

    static final String FIELD_CHUNK_ID = "chunk_id";
    static final String FIELD_TENANT = "tenant";
    static final String FIELD_DOC_ID = "doc_id";
    static final String FIELD_EMBEDDING = "embedding";

    private final MilvusServiceClient client;
    private final String collection;
    private final int dim;
    private final AtomicReference<GenerationId> activeGeneration = new AtomicReference<>();

    public MilvusVectorRetriever(MilvusServiceClient client, String collection, int dim) {
        this.client = Objects.requireNonNull(client, "client");
        this.collection = Objects.requireNonNull(collection, "collection");
        if (dim < 1) {
            throw new IllegalArgumentException("dim must be >= 1");
        }
        this.dim = dim;
        ensureCollection();
    }

    private void ensureCollection() {
        var has = client.hasCollection(
                io.milvus.param.collection.HasCollectionParam.newBuilder()
                        .withCollectionName(collection)
                        .build());
        check(has, "hasCollection");
        if (Boolean.TRUE.equals(has.getData())) {
            client.loadCollection(
                    io.milvus.param.collection.LoadCollectionParam.newBuilder()
                            .withCollectionName(collection)
                            .build());
            return;
        }
        List<FieldType> fields = List.of(
                FieldType.newBuilder()
                        .withName(FIELD_CHUNK_ID)
                        .withDataType(io.milvus.grpc.DataType.VarChar)
                        .withPrimaryKey(true)
                        .withMaxLength(64)
                        .build(),
                FieldType.newBuilder()
                        .withName(FIELD_TENANT)
                        .withDataType(io.milvus.grpc.DataType.VarChar)
                        .withMaxLength(64)
                        .build(),
                FieldType.newBuilder()
                        .withName(FIELD_DOC_ID)
                        .withDataType(io.milvus.grpc.DataType.VarChar)
                        .withMaxLength(64)
                        .build(),
                FieldType.newBuilder()
                        .withName(FIELD_EMBEDDING)
                        .withDataType(io.milvus.grpc.DataType.FloatVector)
                        .withDimension(dim)
                        .build());
        check(
                client.createCollection(
                        CreateCollectionParam.newBuilder()
                                .withCollectionName(collection)
                                .withFieldTypes(fields)
                                .build()),
                "createCollection");
        check(
                client.createIndex(
                        CreateIndexParam.newBuilder()
                                .withCollectionName(collection)
                                .withFieldName(FIELD_EMBEDDING)
                                .withIndexType(IndexType.HNSW)
                                .withMetricType(MetricType.COSINE)
                                .withExtraParam("{\"M\": \"16\", \"efConstruction\": \"200\"}")
                                .withSyncMode(Boolean.TRUE)
                                .build()),
                "createIndex");
        check(
                client.loadCollection(
                        io.milvus.param.collection.LoadCollectionParam.newBuilder()
                                .withCollectionName(collection)
                                .build()),
                "loadCollection");
    }

    @Override
    public CompletionStage<VectorSearchResult> search(SecurityContext context, VectorSearchRequest request) {
        String tenant = request.eligibility().tenantScope().tenantId();
        List<Float> query = new ArrayList<>(request.queryEmbedding().length);
        for (float value : request.queryEmbedding()) {
            query.add(value);
        }
        SearchParam param = SearchParam.newBuilder()
                .withCollectionName(collection)
                .withMetricType(MetricType.COSINE)
                .withVectorFieldName(FIELD_EMBEDDING)
                .withLimit((long) request.topK())
                .withFloatVectors(List.of(query))
                .withOutFields(List.of(FIELD_CHUNK_ID, FIELD_DOC_ID))
                .withExpr(FIELD_TENANT + " == \"" + escape(tenant) + "\"")
                .build();
        SearchResultsWrapper wrapper;
        try {
            var response = client.search(param);
            check(response, "search");
            wrapper = new SearchResultsWrapper(response.getData().getResults());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(map(e));
        }
        try {
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            List<SearchHit> hits = new ArrayList<>(scores.size());
            for (var score : scores) {
                Object doc = score.getFieldValues().get(FIELD_DOC_ID);
                hits.add(
                        new SearchHit(
                                ChunkId.of(score.getStrID()),
                                new org.synanton.storage.contract.DocumentId(
                                        doc == null ? "" : doc.toString()),
                                score.getScore(),
                                "",
                                Map.of()));
            }
            return CompletableFuture.completedFuture(new VectorSearchResult(hits, hits.size()));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(map(e));
        }
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
        try {
            adoptGeneration(vectors);
            List<com.google.gson.JsonObject> rows = new ArrayList<>(vectors.size());
            for (VectorProjection vector : vectors) {
                if (vector.embedding().length != dim) {
                    return CompletableFuture.failedFuture(
                            new StorageException(
                                    StorageErrorKind.UNSUPPORTED,
                                    "embedding dim " + vector.embedding().length
                                            + " does not match collection dim " + dim));
                }
                com.google.gson.JsonArray values = new com.google.gson.JsonArray();
                for (float value : vector.embedding()) {
                    values.add(value);
                }
                com.google.gson.JsonObject row = new com.google.gson.JsonObject();
                row.addProperty(FIELD_CHUNK_ID, vector.chunkId().value());
                row.addProperty(FIELD_TENANT, vector.tenantId());
                row.addProperty(FIELD_DOC_ID, vector.documentId().value());
                row.add(FIELD_EMBEDDING, values);
                rows.add(row);
            }
            check(
                    client.upsert(
                            UpsertParam.newBuilder()
                                    .withCollectionName(collection)
                                    .withRows(rows)
                                    .build()),
                    "upsert");
            check(
                    client.flush(
                            io.milvus.param.collection.FlushParam.newBuilder()
                                    .withCollectionNames(List.of(collection))
                                    .build()),
                    "flush");
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(map(e));
        }
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
        List<String> quoted = new ArrayList<>();
        for (ChunkId id : ids) {
            quoted.add("\"" + escape(id.value()) + "\"");
        }
        try {
            check(
                    client.delete(
                            DeleteParam.newBuilder()
                                    .withCollectionName(collection)
                                    .withExpr(FIELD_CHUNK_ID + " in [" + String.join(",", quoted) + "]")
                                    .build()),
                    "delete");
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(map(e));
        }
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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void check(io.milvus.param.R<?> response, String operation) {
        if (response.getStatus() != io.milvus.param.R.Status.Success.getCode()) {
            throw new StorageException(
                    StorageErrorKind.UNAVAILABLE,
                    "milvus " + operation + " failed: " + response.getMessage());
        }
    }

    private static StorageException map(Exception e) {
        if (e instanceof StorageException storage) {
            return storage;
        }
        return new StorageException(StorageErrorKind.UNAVAILABLE, "milvus failure: " + e.getMessage());
    }
}
