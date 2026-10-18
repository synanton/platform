package org.synanton.synflux.pipeline.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.KnowledgeProcessingRunRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ResourceUsage;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Publishes canonical knowledge (Design 1.25 §5): deterministic semantic chunks
 * with full provenance, recorded in a Processing Run (§12). Chunks reference the
 * SourceVersion they derive from and the run that produced them; the manifest
 * links the Content Cache artifact to both references. Reprocessing the same
 * inputs with the same processing version is idempotent — the run ID, chunk
 * rows, and manifest are deterministic functions of those inputs.
 */
public class PersistStage implements PipelineStage<ChunkedDocument, ChunkedDocument> {

    private static final Logger log = LoggerFactory.getLogger(PersistStage.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRODUCER = "synflux-knowledge";

    private final IngestionCacheClient cacheClient;

    public PersistStage(IngestionCacheClient cacheClient) {
        this.cacheClient = cacheClient;
    }

    @Override
    public String name() { return "persist"; }

    @Override
    public ChunkedDocument apply(ChunkedDocument doc, StageContext ctx) {
        var acquired = doc.parsed().acquired();
        Objects.requireNonNull(ctx.sourceVersionId(),
            "PersistStage requires StageContext.withSourceVersionId — chunks must reference their source version");
        String ingestUsageJson = ResourceUsage.fromStages(ctx.usage().stages()).toJson();
        String state = ctx.props().pipeline().embeddingEnabled() ? "EMBEDDED" : "CHUNKED";
        String configurationJson = configurationJson(ctx);
        UUID processingRunId = processingRunId(ctx.tenant(), acquired.contentRefId(), configurationJson);

        Instant startedAt = Instant.now();
        cacheClient.upsertKnowledgeProcessingRun(new KnowledgeProcessingRunRow(
            ctx.tenant(), processingRunId, PRODUCER, producerVersion(ctx), configurationJson,
            inputJson(acquired, ctx), null, "RUNNING", startedAt, null, null, null
        ));
        try {
            List<ChunkRow> chunkRows = doc.chunks().stream()
                .map(c -> toChunkRow(ctx, acquired.contentRefId(), ctx.sourceVersionId(), processingRunId, c))
                .collect(Collectors.toList());
            if (!chunkRows.isEmpty()) {
                cacheClient.insertChunks(chunkRows);
            }
            cacheClient.upsertManifest(new ManifestRow(
                ctx.tenant(), acquired.contentRefId(), Instant.now(),
                1, "semantic-v1", 1, state, "CONTENT_CACHE", "",
                acquired.sourceUri(), acquired.sha256(), acquired.bytes().length,
                acquired.mimeType(), "FULL", null, ingestUsageJson, acquired.contentCacheReference(),
                processingRunId
            ));
            String outputJson = toJson(Map.of(
                "content_ref_id", acquired.contentRefId().toString(),
                "source_version_id", ctx.sourceVersionId().toString(),
                "chunk_count", chunkRows.size()
            ));
            cacheClient.upsertKnowledgeProcessingRun(new KnowledgeProcessingRunRow(
                ctx.tenant(), processingRunId, PRODUCER, producerVersion(ctx), configurationJson,
                inputJson(acquired, ctx), outputJson, "COMPLETED", startedAt, Instant.now(), null, null
            ));
            log.info("Published {} canonical chunks for ref={} version={} run={}",
                chunkRows.size(), acquired.contentRefId(), ctx.sourceVersionId(), processingRunId);
        } catch (Exception failure) {
            log.warn("Knowledge processing failed for ref={} run={}", acquired.contentRefId(), processingRunId, failure);
            cacheClient.upsertKnowledgeProcessingRun(new KnowledgeProcessingRunRow(
                ctx.tenant(), processingRunId, PRODUCER, producerVersion(ctx), configurationJson,
                inputJson(acquired, ctx), null, "FAILED", startedAt, Instant.now(),
                failure.getClass().getSimpleName(), message(failure)
            ));
            throw failure;
        }
        return doc;
    }

    private static ChunkRow toChunkRow(
            StageContext ctx, java.util.UUID contentRefId, java.util.UUID sourceVersionId,
            UUID processingRunId, SemanticChunk c) {
        return new ChunkRow(
            ctx.tenant(), contentRefId, c.ordinal(), c.text(), c.sha256(),
            c.pageStart(), c.pageEnd(),
            c.sectionPath() == null ? "" : String.join(" > ", c.sectionPath()),
            c.type() == null ? "" : c.type().name(),
            c.heading() == null ? "" : c.heading(),
            toJson(c.sourceElements()),
            c.tokenCount(),
            toJson(c.structuredContent()),
            c.isPartialSection(),
            c.classification() == null ? SemanticChunk.PUBLIC_ONLY : c.classification(),
            c.sectionId() == null ? "" : c.sectionId(),
            c.parentSectionId() == null ? "" : c.parentSectionId(),
            c.headingLevel(),
            sourceVersionId,
            processingRunId
        );
    }

    private static UUID processingRunId(String tenantId, java.util.UUID contentRefId, String configurationJson) {
        String identity = "synflux-knowledge-run:" + tenantId + ":" + contentRefId
            + ":" + configurationJson;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static String producerVersion(StageContext ctx) {
        var chunk = ctx.props().ingest().chunk();
        return (chunk != null && chunk.strategy() != null ? chunk.strategy() : "semantic-v1") + ":1";
    }

    private static String configurationJson(StageContext ctx) {
        var chunk = ctx.props().ingest().chunk();
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("chunk_strategy", chunk != null ? chunk.strategy() : "semantic-v1");
        configuration.put("chunk_strategy_version", 1);
        configuration.put("target_tokens", chunk != null ? chunk.targetTokens() : 512);
        configuration.put("overlap_tokens", chunk != null ? chunk.overlapTokens() : 50);
        configuration.put("annotation_enabled", ctx.props().pipeline().annotationEnabled());
        configuration.put("enrichment_enabled", ctx.props().pipeline().enrichmentEnabled());
        configuration.put("embedding_enabled", ctx.props().pipeline().embeddingEnabled());
        return toJson(configuration);
    }

    private static String inputJson(org.synanton.synflux.domain.AcquiredDocument artifact, StageContext ctx) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("content_ref_id", artifact.contentRefId().toString());
        input.put("content_cache_reference", artifact.contentCacheReference());
        input.put("source_version_id", ctx.sourceVersionId() == null ? "" : ctx.sourceVersionId().toString());
        input.put("source_uri", artifact.sourceUri());
        input.put("source_sha256", artifact.sha256());
        input.put("size_bytes", artifact.bytes().length);
        input.put("mime_type", artifact.mimeType());
        return toJson(input);
    }

    private static String message(Exception failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize knowledge field", e);
        }
    }
}
