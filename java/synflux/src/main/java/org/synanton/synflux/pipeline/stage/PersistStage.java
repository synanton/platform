package org.synanton.synflux.pipeline.stage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.KnowledgeProcessingRunRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Publishes canonical knowledge (Design 1.25 §5): deterministic semantic chunks
 * with full provenance, recorded in a Processing Run (§12).
 *
 * <p>Provenance model: a chunk is a content-addressed knowledge unit. Identical
 * bytes delivered under two source versions derive the same chunks; the chunk
 * attributes itself to every observing source version
 * ({@code source_version_ids}), so each version's provenance path resolves
 * without forking the deduped artifact. The run accumulates the same set.
 *
 * <p>Ordering guarantee: the run record is upserted RUNNING before any chunk
 * write — chunks never exist without a run row. A persist failure records the
 * run FAILED; recovery is the deterministic re-run, which overwrites the same
 * chunk rows (identical run ID and chunk identity) and completes. Search-side
 * visibility of chunks under a FAILED run is filtered by run status at the
 * search-projection boundary (#255); chunk visibility after source-version
 * deletion is likewise the search plane's eligibility check — this stage emits
 * the tombstone reference and does not filter.
 *
 * <p>Idempotency: the run ID is a deterministic function of tenant, artifact,
 * producer version, and canonicalized configuration (sorted keys). Reprocessing
 * the same inputs with the same processing version reproduces identical state.
 * A processing-version change produces a new run and re-derived chunks that
 * replace the prior rendition at the artifact's keys — canonical knowledge
 * reflects the current processing version, and the prior run row remains as
 * execution history.
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
        UUID processingRunId = processingRunId(ctx.tenant(), acquired.contentRefId(), ctx.sourceVersionId(),
            producerVersion(ctx), configurationJson);

        // The run record is upserted RUNNING before any chunk write: chunks never
        // exist without a run row (§251 review, atomicity finding). Attribution: each
        // run records the single source version it processes; the chunk's
        // source_version_ids set accumulates across runs via a server-side delta, so
        // concurrent runs cannot lose an attribution (§251 review, set-race finding).
        Set<UUID> provenanceVersions = java.util.Set.of(ctx.sourceVersionId());
        Instant startedAt = Instant.now();
        cacheClient.upsertKnowledgeProcessingRun(new KnowledgeProcessingRunRow(
            ctx.tenant(), processingRunId, PRODUCER, producerVersion(ctx), configurationJson,
            inputJson(doc.parsed(), ctx), null, "RUNNING", startedAt, null, null, null, provenanceVersions
        ));
        try {
            List<ChunkRow> chunkRows = doc.chunks().stream()
                .map(c -> toChunkRow(ctx, acquired.contentRefId(), provenanceVersions, processingRunId, c))
                .collect(Collectors.toList());
            if (!chunkRows.isEmpty()) {
                cacheClient.recordKnowledgeChunks(
                    ctx.tenant(), acquired.contentRefId(), ctx.sourceVersionId(), processingRunId, chunkRows);
            }
            // PUBLISHING gate: the manifest is written only after the chunks are
            // durable, and the run only transitions COMPLETED after the manifest is
            // durable. Readers filter manifests on run status = COMPLETED, so a crash
            // in this window leaves the manifest invisible — partial knowledge is
            // never published as successful.
            cacheClient.upsertKnowledgeProcessingRun(new KnowledgeProcessingRunRow(
                ctx.tenant(), processingRunId, PRODUCER, producerVersion(ctx), configurationJson,
                inputJson(doc.parsed(), ctx), null, "PUBLISHING", startedAt, null, null, null,
                provenanceVersions
            ));
            cacheClient.upsertManifest(new ManifestRow(
                ctx.tenant(), acquired.contentRefId(), Instant.now(),
                1, "semantic-v1", 1, state, "CONTENT_CACHE", "",
                acquired.sourceUri(), acquired.sha256(), acquired.bytes().length,
                acquired.mimeType(), "FULL", null, ingestUsageJson, acquired.contentCacheReference(),
                processingRunId
            ));
            String outputJson = toJson(Map.of(
                "content_ref_id", acquired.contentRefId().toString(),
                "source_version_ids", provenanceVersions.stream().map(UUID::toString).sorted().toList(),
                "chunk_count", chunkRows.size()
            ));
            cacheClient.upsertKnowledgeProcessingRun(new KnowledgeProcessingRunRow(
                ctx.tenant(), processingRunId, PRODUCER, producerVersion(ctx), configurationJson,
                inputJson(doc.parsed(), ctx), outputJson, "COMPLETED", startedAt, Instant.now(), null, null,
                provenanceVersions
            ));
            log.info("Published {} canonical chunks for ref={} versions={} run={}",
                chunkRows.size(), acquired.contentRefId(), provenanceVersions, processingRunId);
        } catch (Exception failure) {
            log.warn("Knowledge processing failed for ref={} run={}", acquired.contentRefId(), processingRunId, failure);
            cacheClient.upsertKnowledgeProcessingRun(new KnowledgeProcessingRunRow(
                ctx.tenant(), processingRunId, PRODUCER, producerVersion(ctx), configurationJson,
                inputJson(doc.parsed(), ctx), null, "FAILED", startedAt, Instant.now(),
                failure.getClass().getSimpleName(), message(failure), provenanceVersions
            ));
            throw failure;
        }
        return doc;
    }

    /** {@code sourceVersionIds} on the written row is the append delta: this run's version. */
    private static ChunkRow toChunkRow(
            StageContext ctx, java.util.UUID contentRefId, Set<UUID> sourceVersionIds,
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
            Set.copyOf(sourceVersionIds),
            processingRunId,
            c.documentOffsetStart(),
            c.documentOffsetEnd()
        );
    }

    /**
     * Deterministic run identity: tenant, artifact, source version, producer
     * version, and canonicalized configuration (sorted keys). Any change —
     * including a processing-version bump — produces a new run.
     */
    private static UUID processingRunId(
            String tenantId, java.util.UUID contentRefId, java.util.UUID sourceVersionId,
            String producerVersion, String configurationJson) {
        String identity = "synflux-knowledge-run:" + tenantId + ":" + contentRefId
            + ":" + sourceVersionId + ":" + producerVersion + ":" + configurationJson;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static String producerVersion(StageContext ctx) {
        var chunk = ctx.props().ingest().chunk();
        return (chunk != null && chunk.strategy() != null ? chunk.strategy() : "semantic-v1") + ":1";
    }

    private static String configurationJson(StageContext ctx) {
        // TreeMap canonicalizes the configuration (sorted keys) before hashing, so
        // equivalent configurations hash identically regardless of construction order.
        Map<String, Object> configuration = new TreeMap<>();
        var chunk = ctx.props().ingest().chunk();
        configuration.put("chunk_strategy", chunk != null ? chunk.strategy() : "semantic-v1");
        configuration.put("chunk_strategy_version", 1);
        configuration.put("target_tokens", chunk != null ? chunk.targetTokens() : 512);
        configuration.put("overlap_tokens", chunk != null ? chunk.overlapTokens() : 50);
        configuration.put("annotation_enabled", ctx.props().pipeline().annotationEnabled());
        configuration.put("enrichment_enabled", ctx.props().pipeline().enrichmentEnabled());
        configuration.put("embedding_enabled", ctx.props().pipeline().embeddingEnabled());
        return toJson(configuration);
    }

    private static String inputJson(ParsedDocument parsed, StageContext ctx) {
        var artifact = parsed.acquired();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("content_ref_id", artifact.contentRefId().toString());
        input.put("content_cache_reference", artifact.contentCacheReference());
        input.put("source_uri", artifact.sourceUri());
        input.put("source_sha256", artifact.sha256());
        input.put("size_bytes", artifact.bytes().length);
        input.put("mime_type", artifact.mimeType());
        input.put("extraction_text_sha256", sha256(parsed.text()));

        var payload = parsed.documentPayload();
        input.put("extraction_payload_status", payload == null ? "ABSENT" : "PRESENT");
        Map<String, String> descriptor = new TreeMap<>(parsed.payloadDescriptor());
        if (!descriptor.isEmpty()) {
            input.put("extraction_schema_id", descriptor.getOrDefault("schema_id", ""));
            input.put("extraction_schema_version", descriptor.getOrDefault("schema_version", ""));
            input.put("extraction_processor_id", descriptor.getOrDefault("processor_id", ""));
            input.put("extraction_processor_version", descriptor.getOrDefault("processor_version", ""));
            input.put("extraction_payload_format", descriptor.getOrDefault("format", ""));
            input.put("extraction_schema_digest", descriptor.getOrDefault("schema_digest", ""));
            input.put("extraction_payload_digest", descriptor.getOrDefault("payload_digest", ""));
        }
        if (payload != null) {
            if (payload.hasIdentity()) {
                var identity = payload.getIdentity();
                input.put("extraction_document_id", identity.getDocumentId());
                input.put("extraction_document_version", identity.getDocumentVersion());
                input.put("extraction_document_class", identity.getDocumentClass().name());
            }
            if (payload.hasSource()) {
                var source = payload.getSource();
                input.put("extraction_source_media_type", source.getMediaType());
                input.put("extraction_source_format", source.getSourceFormat());
                input.put("extraction_source_digest", source.getSourceDigest());
                input.put("extraction_source_version", source.getSourceVersion());
                input.put("extraction_source_uri", source.getSourceUri());
                putIfPresent(input, "extraction_source_filename", source.getFilename());
            }
            input.put("extraction_element_count", payload.getElementsCount());
            input.put("extraction_source_location_count", payload.getElementsList().stream()
                .mapToInt(element -> element.getSourceLocationsCount())
                .sum());
            input.put("extraction_canonical_range_count", payload.getElementsList().stream()
                .filter(org.synanton.extraction.v1.DocumentElement::hasCanonicalTextRange)
                .count());
            input.put("extraction_projection_count", payload.getProjectionsCount());
            input.put("extraction_projection_formats", payload.getProjectionsList().stream()
                .map(org.synanton.extraction.v1.DocumentProjection::getFormat)
                .filter(format -> !format.isBlank())
                .toList());
            input.put("extraction_projections", payload.getProjectionsList().stream()
                .map(PersistStage::projection)
                .toList());
            input.put("extraction_execution_count", payload.getProvenance().getExecutionsCount());
            input.put("extraction_primary_execution_id", payload.getProvenance().getPrimaryExecutionId());
            input.put("extraction_executions", payload.getProvenance().getExecutionsList().stream()
                .map(PersistStage::execution)
                .toList());
            input.put("extraction_flattened_text_present", !payload.getFlattenedText().isEmpty());
        }
        input.put("extraction_feature_states", new TreeMap<>(parsed.featureStates()));
        return toJson(input);
    }

    private static Map<String, Object> execution(org.synanton.extraction.v1.ExtractionExecution execution) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("execution_id", execution.getExecutionId());
        value.put("extractor_id", execution.getExtractorId());
        value.put("extractor_version", execution.getExtractorVersion());
        putIfPresent(value, "engine_id", execution.getEngineId());
        putIfPresent(value, "engine_version", execution.getEngineVersion());
        putIfPresent(value, "stage_id", execution.getStageId());
        putIfPresent(value, "policy_id", execution.getPolicyId());
        putIfPresent(value, "policy_version", execution.getPolicyVersion());
        value.put("outcome", execution.getOutcome().name());
        value.put("parents", execution.getParentsList().stream()
            .map(parent -> {
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("execution_id", parent.getExecutionId());
                link.put("kind", parent.getKind().name());
                return link;
            })
            .toList());
        if (!execution.getAttributesMap().isEmpty()) {
            value.put("attributes", new TreeMap<>(execution.getAttributesMap()));
        }
        if (execution.hasQuality()) {
            var quality = execution.getQuality();
            value.put("quality_status", quality.getStatus().name());
            if (quality.hasCompleteness()) {
                value.put("quality_completeness", quality.getCompleteness());
            }
            if (quality.hasConfidence()) {
                value.put("quality_confidence", quality.getConfidence());
            }
            value.put("quality_warnings", quality.getWarningsList());
        } else {
            value.put("quality_status", "QUALITY_STATUS_UNSPECIFIED");
        }
        return value;
    }

    private static Map<String, Object> projection(org.synanton.extraction.v1.DocumentProjection projection) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("projection_id", projection.getProjectionId());
        value.put("format", projection.getFormat());
        value.put("contract_id", projection.getContractId());
        value.put("contract_version", projection.getContractVersion());
        value.put("outcome", projection.getOutcome().name());
        value.put("digest", projection.getDigest());
        value.put("unsupported_reason", projection.getUnsupportedReason());
        value.put("content_available", projection.hasInlineContent() || projection.hasReference());
        if (projection.hasLoss()) {
            var loss = projection.getLoss();
            Map<String, Object> lossValue = new LinkedHashMap<>();
            lossValue.put("dropped", loss.getDroppedList());
            lossValue.put("approximated", loss.getApproximatedList());
            lossValue.put("unsupported", loss.getUnsupportedList());
            lossValue.put("reasons", loss.getReasonsList());
            value.put("loss", lossValue);
        }
        return value;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
