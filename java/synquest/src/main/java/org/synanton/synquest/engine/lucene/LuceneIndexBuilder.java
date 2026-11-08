package org.synanton.synquest.engine.lucene;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.FSDirectory;
import org.synanton.ingestioncache.client.IngestionCacheClient;import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.EmbeddingRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;

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
    private static final String FIELD_SOURCE_ELEMENTS = "source_elements";
    private static final String FIELD_TOKEN_COUNT = "token_count";
    private static final String FIELD_STRUCTURED_CONTENT = "structured_content";
    private static final String FIELD_IS_PARTIAL_SECTION = "is_partial_section";
    private static final String FIELD_CLASSIFICATION = "classification";
    private static final String FIELD_INGEST_USAGE = "ingest_usage";
    private static final String FIELD_INGEST_WALL_MS = "ingest_wall_ms";
    // B2/T05 hierarchy: section_key = "<content_ref_id>|<section_id>" (sibling lookup for expand=section)
    static final String FIELD_SECTION_KEY = "section_key";
    static final String FIELD_SECTION_ID = "section_id";
    private static final String FIELD_PARENT_SECTION_ID = "parent_section_id";
    private static final String FIELD_HEADING_LEVEL = "heading_level";
    private static final String FIELD_CHUNK_TYPE = "chunk_type";

    private static final int MANIFEST_LIMIT = 200_000;
    private static final java.util.Set<String> INDEXABLE_STATES =
            java.util.Set.of("CHUNKED", "ENRICHED", "EMBEDDED");

    private final IngestionCacheClient cacheClient;
    private final String indexBasePath;
    private final EmbeddingShape shape;
    private final String embeddingModel;
    private final boolean embeddingRequired;
    private final java.util.Map<String, BuildReport> lastReports = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Outcome of the last {@link #build} per tenant, surfaced on {@code /index/stats}. A dense
     * benchmark run is only valid when {@code vectorDocs == docs}, i.e. no dimension mismatches
     * and no missing vectors.
     */
    public record BuildReport(String embeddingModel, int embeddingDim, boolean truncated,
                              int docs, int vectorDocs, int dimMismatches, int missingVectors, int sectionDocs) {}

    @org.springframework.beans.factory.annotation.Autowired
    public LuceneIndexBuilder(IngestionCacheClient cacheClient,
                              org.synanton.synquest.config.SynquestProperties props,
                              EmbeddingShape shape,
                              @org.springframework.beans.factory.annotation.Value("${synquest.embedding.required:false}")
                              boolean embeddingRequired) {
        this.cacheClient = cacheClient;
        this.indexBasePath = props.index().path();
        this.shape = shape;
        this.embeddingModel = props.embedding().model();
        this.embeddingRequired = embeddingRequired;
    }

    public LuceneIndexBuilder(IngestionCacheClient cacheClient,
                              org.synanton.synquest.config.SynquestProperties props) {
        this(cacheClient, props, new EmbeddingShape(props), false);
    }

    public java.util.Optional<BuildReport> lastReport(String tenant) {
        return java.util.Optional.ofNullable(lastReports.get(tenant));
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

    /**
     * Build (or rebuild) the Lucene index for the given tenant from Cassandra.
     *
     * <p>Streams manifests and chunks through the driver's paging (fetch size
     * 1,000) instead of materialising the tenant on the heap. Lucene's writer
     * already flushes segments when its RAM buffer fills, so a single final
     * commit is memory-bounded and crash-safe: a failed rebuild never leaves a
     * partially committed index that a later boot would mistake for complete.
     */
    public void build(String tenant) throws IOException {
        Path path = indexPath(tenant);
        Files.createDirectories(path);

        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        Counters counters = new Counters();
        int skipCount = 0;

        try (FSDirectory dir = FSDirectory.open(path);
             IndexWriter writer = new IndexWriter(dir, config)) {

            for (ManifestRow manifest : cacheClient.manifestCursor(tenant, MANIFEST_LIMIT)) {
                if (!isIndexable(manifest)) {
                    skipCount++;
                    continue;
                }
                String ingestUsage = manifest.ingestUsage() == null ? "" : manifest.ingestUsage();
                long ingestWallMs = parseIngestWallMs(ingestUsage);
                for (ChunkRow chunk : cacheClient.chunkCursor(tenant, manifest.contentRefId())) {
                    writer.addDocument(newDocument(manifest, chunk, ingestUsage, ingestWallMs, counters));
                    counters.docs++;
                }
            }

            writer.commit();
        }
        finishBuild(tenant, counters, skipCount);
    }

    /**
     * Incrementally upserts the given content refs (Phase 2, #547): reopens the
     * existing index with {@code CREATE_OR_APPEND}, replaces each chunk by its
     * stable id, and commits. A re-delivered event updates the document instead
     * of duplicating it.
     */
    public int upsertContentRefs(String tenant, java.util.Collection<UUID> contentRefIds) throws IOException {
        if (contentRefIds == null || contentRefIds.isEmpty()) {
            return 0;
        }
        Path path = indexPath(tenant);
        Files.createDirectories(path);
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);

        Counters counters = new Counters();
        try (FSDirectory dir = FSDirectory.open(path);
             IndexWriter writer = new IndexWriter(dir, config)) {
            for (UUID contentRefId : new java.util.LinkedHashSet<>(contentRefIds)) {
                ManifestRow manifest = cacheClient.readManifest(tenant, contentRefId).orElse(null);
                if (manifest == null || !isIndexable(manifest)) {
                    continue;
                }
                String ingestUsage = manifest.ingestUsage() == null ? "" : manifest.ingestUsage();
                long ingestWallMs = parseIngestWallMs(ingestUsage);
                for (ChunkRow chunk : cacheClient.chunkCursor(tenant, contentRefId)) {
                    writer.updateDocument(
                            new org.apache.lucene.index.Term(FIELD_ID,
                                    manifest.contentRefId() + ":" + chunk.chunkOrdinal()),
                            newDocument(manifest, chunk, ingestUsage, ingestWallMs, counters));
                    counters.docs++;
                }
            }
            writer.commit();
        }
        log.info("Incrementally projected {} chunk(s) from {} content ref(s) for tenant '{}'",
                counters.docs, contentRefIds.size(), tenant);
        return counters.docs;
    }

    /** Deletes every chunk of the given content refs (incremental source deletion). */
    public int deleteContentRefs(String tenant, java.util.Collection<UUID> contentRefIds) throws IOException {
        if (contentRefIds == null || contentRefIds.isEmpty()) {
            return 0;
        }
        Path path = indexPath(tenant);
        Files.createDirectories(path);
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        try (FSDirectory dir = FSDirectory.open(path);
             IndexWriter writer = new IndexWriter(dir, config)) {
            for (UUID contentRefId : new java.util.LinkedHashSet<>(contentRefIds)) {
                writer.deleteDocuments(new org.apache.lucene.index.Term(FIELD_CONTENT_REF_ID, contentRefId.toString()));
            }
            writer.commit();
        }
        return contentRefIds.size();
    }

    /** Deletes chunks by their stable Lucene id (used by delete projection). */
    public int deleteChunkIds(String tenant, java.util.Collection<String> chunkIds) throws IOException {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        Path path = indexPath(tenant);
        Files.createDirectories(path);
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        try (FSDirectory dir = FSDirectory.open(path);
             IndexWriter writer = new IndexWriter(dir, config)) {
            for (String chunkId : new java.util.LinkedHashSet<>(chunkIds)) {
                writer.deleteDocuments(new org.apache.lucene.index.Term(FIELD_ID, chunkId));
            }
            writer.commit();
        }
        return chunkIds.size();
    }

    private Document newDocument(ManifestRow manifest, ChunkRow chunk, String ingestUsage,
                                 long ingestWallMs, Counters counters) {
        var embOpt = cacheClient.readEmbedding(
                manifest.tenantId(), manifest.contentRefId(), chunk.chunkOrdinal(), embeddingModel);

        Document doc = new Document();
        String id = manifest.contentRefId() + ":" + chunk.chunkOrdinal();
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
        doc.add(new StoredField(FIELD_SOURCE_ELEMENTS, chunk.sourceElementsJson() == null ? "[]" : chunk.sourceElementsJson()));
        doc.add(new StoredField(FIELD_TOKEN_COUNT, chunk.tokenCount()));
        doc.add(new StoredField(FIELD_STRUCTURED_CONTENT, chunk.structuredContentJson() == null ? "" : chunk.structuredContentJson()));
        doc.add(new StoredField(FIELD_IS_PARTIAL_SECTION, String.valueOf(chunk.isPartialSection())));
        for (String cls : chunk.classification()) {
            doc.add(new StringField(FIELD_CLASSIFICATION, cls, Field.Store.YES));
        }
        doc.add(new StoredField(FIELD_INGEST_USAGE, ingestUsage));
        if (chunk.chunkType() != null && !chunk.chunkType().isEmpty()) {
            doc.add(new StringField(FIELD_CHUNK_TYPE, chunk.chunkType(), Field.Store.YES));
        }
        if (chunk.sectionId() != null && !chunk.sectionId().isEmpty()) {
            doc.add(new StringField(FIELD_SECTION_KEY,
                    manifest.contentRefId() + "|" + chunk.sectionId(), Field.Store.NO));
            doc.add(new StoredField(FIELD_SECTION_ID, chunk.sectionId()));
            doc.add(new StoredField(FIELD_PARENT_SECTION_ID,
                    chunk.parentSectionId() == null ? "" : chunk.parentSectionId()));
            doc.add(new StoredField(FIELD_HEADING_LEVEL, chunk.headingLevel()));
            counters.sectionDocs++;
        }
        doc.add(new StoredField(FIELD_INGEST_WALL_MS, ingestWallMs));

        if (embOpt.isPresent()) {
            EmbeddingRow emb = embOpt.get();
            try {
                float[] vec = shape.fit(emb.embedding());
                doc.add(new KnnFloatVectorField(FIELD_EMBEDDING, vec, VectorSimilarityFunction.COSINE));
                counters.vectorDocs++;
            } catch (EmbeddingShape.DimensionMismatchException e) {
                counters.dimMismatches++;
                if (counters.dimMismatches <= 5) {
                    log.warn("Embedding dim mismatch for {}#{}: {}",
                            manifest.contentRefId(), chunk.chunkOrdinal(), e.getMessage());
                }
            }
        } else {
            counters.missingVectors++;
        }
        return doc;
    }

    private static boolean isIndexable(ManifestRow manifest) {
        return INDEXABLE_STATES.contains(manifest.state() == null ? "" : manifest.state().toUpperCase());
    }

    private void finishBuild(String tenant, Counters counters, int skipCount) {
        BuildReport report = new BuildReport(embeddingModel, shape.dim(), shape.truncates(),
                counters.docs, counters.vectorDocs, counters.dimMismatches, counters.missingVectors,
                counters.sectionDocs);
        lastReports.put(tenant, report);
        log.info("Built Lucene index for tenant '{}': {} docs indexed ({} with {}-dim vectors of model '{}', "
                        + "{} dim mismatches, {} without a vector), {} manifests skipped",
                tenant, counters.docs, counters.vectorDocs, shape.describe(), embeddingModel,
                counters.dimMismatches, counters.missingVectors, skipCount);
        if (embeddingRequired && (counters.dimMismatches > 0 || counters.missingVectors > 0)) {
            // Fail closed: under synquest.embedding.required a partly-vectorised index would make
            // a "dense"/"hybrid" run silently lexical for the affected chunks.
            throw new IllegalStateException("index for tenant '" + tenant + "' is not fully vectorised with model '"
                    + embeddingModel + "' at dim " + shape.describe() + ": " + counters.dimMismatches
                    + " dim mismatches, " + counters.missingVectors
                    + " chunks without a vector (synquest.embedding.required=true)");
        }
    }

    private static final class Counters {
        int docs;
        int vectorDocs;
        int dimMismatches;
        int missingVectors;
        int sectionDocs;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static long parseIngestWallMs(String ingestUsageJson) {
        if (ingestUsageJson == null || ingestUsageJson.isBlank()) {
            return 0L;
        }
        try {
            var root = MAPPER.readTree(ingestUsageJson);
            var node = root.path("wallMs");
            if (node.isMissingNode()) {
                node = root.path("wall_ms");
            }
            return node.asLong(0L);
        } catch (Exception e) {
            return 0L;
        }
    }
}
