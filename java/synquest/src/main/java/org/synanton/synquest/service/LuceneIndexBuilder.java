package org.synanton.synquest.service;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.FSDirectory;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

@Component
public class LuceneIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexBuilder.class);
    private static final String FIELD_ID = "id";
    private static final String FIELD_CONTENT_REF_ID = "content_ref_id";
    private static final String FIELD_CHUNK_ORDINAL = "chunk_ordinal";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String FIELD_SOURCE_URI = "source_uri";
    private static final String FIELD_MIME_TYPE = "mime_type";
    private static final String FIELD_PAGE_START = "page_start";
    private static final String FIELD_PAGE_END = "page_end";
    private static final String FIELD_SECTION_PATH = "section_path";
    private static final String FIELD_HEADING = "heading";

    private static final java.util.Set<String> INDEXABLE_STATES =
            java.util.Set.of("CHUNKED", "ENRICHED", "EMBEDDED");

    private final IngestionCacheClient cacheClient;
    private final String indexBasePath;
    private final int embeddingDim;
    private final String embeddingModel;

    public LuceneIndexBuilder(IngestionCacheClient cacheClient,
                              org.synanton.synquest.config.SynquestProperties props) {
        this.cacheClient = cacheClient;
        this.indexBasePath = props.index().path();
        this.embeddingDim = props.embedding().dim();
        this.embeddingModel = props.embedding().model();
    }

    public Path indexPath(String tenant) {
        return Path.of(indexBasePath, tenant);
    }

    public boolean isEmpty(String tenant) {
        Path path = indexPath(tenant);
        if (!Files.exists(path)) return true;
        try (var stream = Files.list(path)) {
            return stream.findAny().isEmpty();
        } catch (IOException e) {
            return true;
        }
    }

    /** Build (or rebuild) the Lucene index for the given tenant from Cassandra. */
    public void build(String tenant) throws IOException {
        Path path = indexPath(tenant);
        Files.createDirectories(path);

        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        int docCount = 0;
        int skipCount = 0;

        try (FSDirectory dir = FSDirectory.open(path);
             IndexWriter writer = new IndexWriter(dir, config)) {

            List<ManifestRow> manifests = cacheClient.listManifest(tenant, 200_000);
            for (ManifestRow manifest : manifests) {
                if (!INDEXABLE_STATES.contains(manifest.state() == null ? "" : manifest.state().toUpperCase())) {
                    skipCount++;
                    continue;
                }

                var chunks = cacheClient.readChunks(tenant, manifest.contentRefId());
                for (var chunk : chunks) {
                    var embOpt = cacheClient.readEmbedding(
                            tenant, manifest.contentRefId(), chunk.chunkOrdinal(), embeddingModel);

                    Document doc = new Document();
                    String id = manifest.contentRefId() + "#" + chunk.chunkOrdinal();
                    doc.add(new StringField(FIELD_ID, id, Field.Store.YES));
                    doc.add(new StringField(FIELD_CONTENT_REF_ID, manifest.contentRefId().toString(), Field.Store.YES));
                    doc.add(new NumericDocValuesField(FIELD_CHUNK_ORDINAL, chunk.chunkOrdinal()));
                    doc.add(new StoredField(FIELD_CHUNK_ORDINAL, chunk.chunkOrdinal()));
                    doc.add(new TextField(FIELD_TEXT, chunk.chunkText() == null ? "" : chunk.chunkText(), Field.Store.YES));
                    if (manifest.sourceUri() != null) {
                        doc.add(new StoredField(FIELD_SOURCE_URI, manifest.sourceUri()));
                    }
                    if (manifest.mimeType() != null) {
                        doc.add(new StoredField(FIELD_MIME_TYPE, manifest.mimeType()));
                    }
                    doc.add(new StoredField(FIELD_PAGE_START, chunk.pageStart()));
                    doc.add(new StoredField(FIELD_PAGE_END, chunk.pageEnd()));
                    doc.add(new StoredField(FIELD_SECTION_PATH, chunk.sectionPath() == null ? "" : chunk.sectionPath()));
                    doc.add(new StoredField(FIELD_HEADING, chunk.heading() == null ? "" : chunk.heading()));

                    if (embOpt.isPresent()) {
                        EmbeddingRow emb = embOpt.get();
                        float[] vec = emb.embedding();
                        if (vec.length == embeddingDim) {
                            vec = QueryEmbedder.normalise(vec);
                            doc.add(new KnnFloatVectorField(FIELD_EMBEDDING, vec, VectorSimilarityFunction.COSINE));
                        } else {
                            log.warn("Embedding dim mismatch for {}#{}: expected {} got {}",
                                    manifest.contentRefId(), chunk.chunkOrdinal(), embeddingDim, vec.length);
                        }
                    }

                    writer.addDocument(doc);
                    docCount++;
                }
            }

            writer.commit();
        }

        log.info("Built Lucene index for tenant '{}': {} docs indexed, {} manifests skipped",
                tenant, docCount, skipCount);
    }
}
