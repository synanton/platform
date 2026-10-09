package org.synanton.synquest.lucene;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.index.VectorSimilarityFunction;import org.apache.lucene.store.FSDirectory;
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
 * Standalone Lucene vector adapter (SYN-VECTOR-001 B3.3): Lucene as a vector
 * engine without Cassandra storage. Exists to resolve the R3 attribution
 * ambiguity — same vector-only numbers as Cassandra+Lucene on identical
 * queries means the vector leg, not the store, explains observed parity.
 * Text/metadata stay in the metadata store: hits return empty text.
 */
public final class LuceneStandaloneVectorRetriever implements VectorRetriever, SynquestIndexWriter {

    static final String FIELD_ID = "id";
    static final String FIELD_TENANT = "tenant";
    static final String FIELD_DOC_ID = "doc_id";
    static final String FIELD_EMBEDDING = "embedding";

    private final Path root;
    private final int dim;
    private final AtomicReference<GenerationId> activeGeneration = new AtomicReference<>();

    public LuceneStandaloneVectorRetriever(Path root, int dim) {
        this.root = Objects.requireNonNull(root, "root");
        if (dim < 1) {
            throw new IllegalArgumentException("dim must be >= 1");
        }
        this.dim = dim;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create index dir: " + root, e);
        }
    }

    @Override
    public CompletionStage<VectorSearchResult> search(SecurityContext context, VectorSearchRequest request) {
        String tenant = request.eligibility().tenantScope().tenantId();
        try (FSDirectory directory = FSDirectory.open(root)) {
            if (!DirectoryReader.indexExists(directory)) {
                return CompletableFuture.completedFuture(new VectorSearchResult(List.of(), 0));
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                BooleanQuery query = new BooleanQuery.Builder()
                        .add(new KnnFloatVectorQuery(
                                FIELD_EMBEDDING, request.queryEmbedding(), request.topK()), BooleanClause.Occur.MUST)
                        .add(new org.apache.lucene.search.TermQuery(
                                new Term(FIELD_TENANT, tenant)), BooleanClause.Occur.FILTER)
                        .build();
                TopDocs top = searcher.search(query, request.topK());
                List<SearchHit> hits = new ArrayList<>(top.scoreDocs.length);
                for (ScoreDoc scoreDoc : top.scoreDocs) {
                    Document document = searcher.storedFields().document(scoreDoc.doc);
                    hits.add(
                            new SearchHit(
                                    ChunkId.of(document.get(FIELD_ID)),
                                    new DocumentId(document.get(FIELD_DOC_ID)),
                                    scoreDoc.score,
                                    "",
                                    Map.of()));
                }
                return CompletableFuture.completedFuture(new VectorSearchResult(hits, hits.size()));
            }
        } catch (IOException e) {
            return CompletableFuture.failedFuture(
                    new StorageException(StorageErrorKind.UNAVAILABLE, "lucene search failed: " + e.getMessage()));
        } catch (RuntimeException e) {
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
        adoptGeneration(vectors);
        Analyzer analyzer = new StandardAnalyzer();
        try (FSDirectory directory = FSDirectory.open(root);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (VectorProjection vector : vectors) {
                if (vector.embedding().length != dim) {
                    return CompletableFuture.failedFuture(
                            new StorageException(
                                    StorageErrorKind.UNSUPPORTED,
                                    "embedding dim " + vector.embedding().length
                                            + " does not match index dim " + dim));
                }
                Document document = new Document();
                document.add(new StringField(FIELD_ID, vector.chunkId().value(), Field.Store.YES));
                document.add(new StringField(FIELD_TENANT, vector.tenantId(), Field.Store.NO));
                document.add(new StoredField(FIELD_DOC_ID, vector.documentId().value()));
                document.add(new KnnFloatVectorField(
                        FIELD_EMBEDDING, vector.embedding(), VectorSimilarityFunction.COSINE));
                writer.updateDocument(new Term(FIELD_ID, vector.chunkId().value()), document);
            }
            writer.commit();
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(
                    new StorageException(StorageErrorKind.UNAVAILABLE, "lucene upsert failed: " + e.getMessage()));
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
        Analyzer analyzer = new StandardAnalyzer();
        try (FSDirectory directory = FSDirectory.open(root);
                IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (ChunkId id : ids) {
                writer.deleteDocuments(new Term(FIELD_ID, id.value()));
            }
            writer.commit();
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(
                    new StorageException(StorageErrorKind.UNAVAILABLE, "lucene delete failed: " + e.getMessage()));
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

    private static StorageException map(Exception e) {
        if (e instanceof StorageException storage) {
            return storage;
        }
        return new StorageException(StorageErrorKind.UNAVAILABLE, "lucene failure: " + e.getMessage());
    }
}
