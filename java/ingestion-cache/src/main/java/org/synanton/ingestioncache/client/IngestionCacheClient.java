package org.synanton.ingestioncache.client;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.synanton.ingestioncache.codec.LZ4EmbeddingCodec;
import org.synanton.ingestioncache.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class IngestionCacheClient {

    private static final Logger log = LoggerFactory.getLogger(IngestionCacheClient.class);
    private final CqlSession session;

    // Caffeine look-aside caches
    private final Cache<String, Optional<String>> analysisInputHashCache = Caffeine.newBuilder()
        .maximumSize(10_000).expireAfterWrite(30, TimeUnit.MINUTES).build();
    private final Cache<String, Optional<float[]>> embeddingChunkHashCache = Caffeine.newBuilder()
        .maximumSize(50_000).expireAfterWrite(60, TimeUnit.MINUTES).build();

    public IngestionCacheClient(CqlSession session) {
        this.session = session;
    }

    // ---- Manifest ----

    public void upsertManifest(ManifestRow row) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.manifest " +
            "(tenant_id, content_ref_id, ingested_at, schema_version, chunk_strategy, chunk_strategy_version, " +
            "state, storage_tier, archive_location, source_uri, source_sha256, size_bytes, mime_type, " +
            "embedding_quality, enrichment_model_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            row.tenantId(), row.contentRefId(), row.ingestedAt(), row.schemaVersion(),
            row.chunkStrategy(), row.chunkStrategyVersion(), row.state(), row.storageTier(),
            row.archiveLocation(), row.sourceUri(), row.sourceSha256(), row.sizeBytes(),
            row.mimeType(), row.embeddingQuality(), row.enrichmentModelId()
        ));
    }

    public Optional<ManifestRow> readManifest(String tenantId, UUID contentRefId) {
        var row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.manifest WHERE tenant_id=? AND content_ref_id=?",
            tenantId, contentRefId
        )).one();
        return row == null ? Optional.empty() : Optional.of(rowToManifest(row));
    }

    public List<ManifestRow> listManifest(String tenantId, int limit) {
        var result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.manifest WHERE tenant_id=? LIMIT ?",
            tenantId, limit
        ));
        List<ManifestRow> rows = new ArrayList<>();
        for (Row r : result) rows.add(rowToManifest(r));
        return rows;
    }

    private ManifestRow rowToManifest(Row r) {
        return new ManifestRow(
            r.getString("tenant_id"), r.getUuid("content_ref_id"),
            r.getInstant("ingested_at"), r.getInt("schema_version"),
            r.getString("chunk_strategy"), r.getInt("chunk_strategy_version"),
            r.getString("state"), r.getString("storage_tier"),
            r.getString("archive_location"), r.getString("source_uri"),
            r.getString("source_sha256"), r.getLong("size_bytes"),
            r.getString("mime_type"), r.getString("embedding_quality"),
            r.getString("enrichment_model_id")
        );
    }

    // ---- Chunks ----

    public void insertChunk(ChunkRow chunk) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.chunks_payload (tenant_id, content_ref_id, chunk_ordinal, chunk_text, chunk_sha256) VALUES (?,?,?,?,?)",
            chunk.tenantId(), chunk.contentRefId(), chunk.chunkOrdinal(), chunk.chunkText(), chunk.chunkSha256()
        ));
    }

    public void insertChunks(List<ChunkRow> chunks) {
        var batch = BatchStatement.newInstance(DefaultBatchType.LOGGED);
        for (ChunkRow c : chunks) {
            batch = batch.add(SimpleStatement.newInstance(
                "INSERT INTO ingestion_cache.chunks_payload (tenant_id, content_ref_id, chunk_ordinal, chunk_text, chunk_sha256) VALUES (?,?,?,?,?)",
                c.tenantId(), c.contentRefId(), c.chunkOrdinal(), c.chunkText(), c.chunkSha256()
            ));
        }
        session.execute(batch);
    }

    public List<ChunkRow> readChunks(String tenantId, UUID contentRefId) {
        var result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.chunks_payload WHERE tenant_id=? AND content_ref_id=?",
            tenantId, contentRefId
        ));
        List<ChunkRow> rows = new ArrayList<>();
        for (Row r : result) {
            rows.add(new ChunkRow(
                r.getString("tenant_id"), r.getUuid("content_ref_id"),
                r.getInt("chunk_ordinal"), r.getString("chunk_text"),
                r.getString("chunk_sha256")
            ));
        }
        return rows;
    }

    // ---- Jobs ----

    public void upsertJob(JobRow job) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.jobs (tenant_id, job_id, started_at, completed_at, state, source, source_path, " +
            "processed_count, error_count, last_error, enriched_count, embedded_count, enrichment_cache_hits, " +
            "embedding_cache_hits, enrichment_errors, embedding_errors, skipped_already_embedded) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            job.tenantId(), job.jobId(), job.startedAt(), job.completedAt(), job.state(),
            job.source(), job.sourcePath(), job.processedCount(), job.errorCount(), job.lastError(),
            job.enrichedCount(), job.embeddedCount(), job.enrichmentCacheHits(), job.embeddingCacheHits(),
            job.enrichmentErrors(), job.embeddingErrors(), job.skippedAlreadyEmbedded()
        ));
    }

    public List<JobRow> listJobs(String tenantId, int limit) {
        var result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.jobs WHERE tenant_id=? LIMIT ?",
            tenantId, limit
        ));
        List<JobRow> rows = new ArrayList<>();
        for (Row r : result) {
            rows.add(new JobRow(
                r.getString("tenant_id"), r.getUuid("job_id"),
                r.getInstant("started_at"), r.getInstant("completed_at"),
                r.getString("state"), r.getString("source"), r.getString("source_path"),
                r.getInt("processed_count"), r.getInt("error_count"), r.getString("last_error"),
                r.getInt("enriched_count"), r.getInt("embedded_count"),
                r.getInt("enrichment_cache_hits"), r.getInt("embedding_cache_hits"),
                r.getInt("enrichment_errors"), r.getInt("embedding_errors"),
                r.getInt("skipped_already_embedded")
            ));
        }
        return rows;
    }

    // ---- Analysis Cache (Phase 2) ----

    public void upsertAnalysis(AnalysisRow row) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.analysis_cache (tenant_id, content_ref_id, chunk_ordinal, pass_number, " +
            "model_id, prompt_version, analysis_json, input_sha256, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            row.tenantId(), row.contentRefId(), row.chunkOrdinal(), row.passNumber(),
            row.modelId(), row.promptVersion(), row.analysisJson(), row.inputSha256(), row.createdAt()
        ));
    }

    public Optional<AnalysisRow> readAnalysis(String tenantId, UUID contentRefId, int chunkOrdinal, int passNumber) {
        var r = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.analysis_cache WHERE tenant_id=? AND content_ref_id=? AND chunk_ordinal=? AND pass_number=?",
            tenantId, contentRefId, chunkOrdinal, passNumber
        )).one();
        return r == null ? Optional.empty() : Optional.of(rowToAnalysis(r));
    }

    public Optional<AnalysisRow> readAnalysisByInputHash(String tenantId, String inputSha256) {
        String cacheKey = tenantId + ":" + inputSha256;
        var cached = analysisInputHashCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.map(json -> new AnalysisRow(tenantId, null, 0, 0, null, null, json, inputSha256, null));
        }
        // Secondary index not available in Cassandra without ALLOW FILTERING or secondary index.
        // For simplicity in Phase 1, iterate (small corpus). Phase 2 can add a secondary index.
        var result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.analysis_cache WHERE tenant_id=? AND input_sha256=? ALLOW FILTERING",
            tenantId, inputSha256
        )).one();
        if (result == null) {
            analysisInputHashCache.put(cacheKey, Optional.empty());
            return Optional.empty();
        }
        var row = rowToAnalysis(result);
        analysisInputHashCache.put(cacheKey, Optional.of(row.analysisJson()));
        return Optional.of(row);
    }

    public List<AnalysisRow> listAnalysis(String tenantId, UUID contentRefId) {
        var rs = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.analysis_cache WHERE tenant_id=? AND content_ref_id=?",
            tenantId, contentRefId
        ));
        List<AnalysisRow> rows = new ArrayList<>();
        for (Row r : rs) rows.add(rowToAnalysis(r));
        return rows;
    }

    private AnalysisRow rowToAnalysis(Row r) {
        return new AnalysisRow(
            r.getString("tenant_id"), r.getUuid("content_ref_id"),
            r.getInt("chunk_ordinal"), r.getInt("pass_number"),
            r.getString("model_id"), r.getString("prompt_version"),
            r.getString("analysis_json"), r.getString("input_sha256"),
            r.getInstant("created_at")
        );
    }

    // ---- Embedding Cache (Phase 2) ----

    public void upsertEmbedding(EmbeddingRow row) {
        byte[] compressed = LZ4EmbeddingCodec.compress(row.embedding());
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.embedding_content_cache (tenant_id, content_ref_id, chunk_ordinal, " +
            "model_id, chunk_sha256, embedding, embedding_dim, created_at) VALUES (?,?,?,?,?,?,?,?)",
            row.tenantId(), row.contentRefId(), row.chunkOrdinal(), row.modelId(),
            row.chunkSha256(), ByteBuffer.wrap(compressed), row.embeddingDim(), row.createdAt()
        ));
    }

    public Optional<EmbeddingRow> readEmbedding(String tenantId, UUID contentRefId, int chunkOrdinal, String modelId) {
        var r = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.embedding_content_cache WHERE tenant_id=? AND content_ref_id=? AND chunk_ordinal=? AND model_id=?",
            tenantId, contentRefId, chunkOrdinal, modelId
        )).one();
        return r == null ? Optional.empty() : Optional.of(rowToEmbedding(r));
    }

    public Optional<EmbeddingRow> readEmbeddingByChunkHash(String tenantId, String chunkSha256, String modelId) {
        String cacheKey = tenantId + ":" + chunkSha256 + ":" + modelId;
        var cached = embeddingChunkHashCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.map(emb -> new EmbeddingRow(tenantId, null, 0, modelId, chunkSha256, emb, emb.length, null));
        }
        var r = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.embedding_content_cache WHERE tenant_id=? AND chunk_sha256=? AND model_id=? ALLOW FILTERING",
            tenantId, chunkSha256, modelId
        )).one();
        if (r == null) {
            embeddingChunkHashCache.put(cacheKey, Optional.empty());
            return Optional.empty();
        }
        var row = rowToEmbedding(r);
        embeddingChunkHashCache.put(cacheKey, Optional.of(row.embedding()));
        return Optional.of(row);
    }

    public List<EmbeddingRow> listEmbeddings(String tenantId, UUID contentRefId) {
        var rs = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.embedding_content_cache WHERE tenant_id=? AND content_ref_id=?",
            tenantId, contentRefId
        ));
        List<EmbeddingRow> rows = new ArrayList<>();
        for (Row r : rs) rows.add(rowToEmbedding(r));
        return rows;
    }

    private EmbeddingRow rowToEmbedding(Row r) {
        ByteBuffer buf = r.getByteBuffer("embedding");
        float[] embedding = buf == null ? new float[0] : LZ4EmbeddingCodec.decompress(buf.array());
        return new EmbeddingRow(
            r.getString("tenant_id"), r.getUuid("content_ref_id"),
            r.getInt("chunk_ordinal"), r.getString("model_id"),
            r.getString("chunk_sha256"), embedding, r.getInt("embedding_dim"),
            r.getInstant("created_at")
        );
    }

    // ---- Outbox (Phase 3) ----

    public void insertOutboxRow(OutboxRow row) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.manifest_transitions_outbox " +
            "(tenant_id, event_id, manifest_id, transition_from, transition_to, topic, payload_json, published, created_at) " +
            "VALUES (?, now(), ?, ?, ?, ?, ?, false, ?)",
            row.tenantId(), row.manifestId(), row.transitionFrom(), row.transitionTo(),
            row.topic(), row.payloadJson(), row.createdAt()
        ));
    }

    public List<OutboxRow> listUnpublishedOutbox(String tenantId, int limit) {
        var rs = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.manifest_transitions_outbox WHERE tenant_id=? AND published=false LIMIT ? ALLOW FILTERING",
            tenantId, limit
        ));
        List<OutboxRow> rows = new ArrayList<>();
        for (Row r : rs) rows.add(rowToOutbox(r));
        return rows;
    }

    public void markOutboxPublished(String tenantId, UUID eventId) {
        session.execute(SimpleStatement.newInstance(
            "UPDATE ingestion_cache.manifest_transitions_outbox SET published=true WHERE tenant_id=? AND event_id=?",
            tenantId, eventId
        ));
    }

    private OutboxRow rowToOutbox(Row r) {
        return new OutboxRow(
            r.getString("tenant_id"),
            r.getUuid("event_id"),
            r.getUuid("manifest_id"),
            r.getString("transition_from"),
            r.getString("transition_to"),
            r.getString("topic"),
            r.getString("payload_json"),
            r.getBoolean("published"),
            r.getInstant("created_at")
        );
    }

    public CqlSession getSession() { return session; }
}
