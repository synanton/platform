package org.synanton.synflux.pipeline.stage;

import org.synanton.extraction.client.ExtractionClientMetrics;
import org.synanton.extraction.client.ExtractionFallbackPolicy;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.client.FallbackExtractionResult;
import org.synanton.extraction.client.LocalTikaFallbackExtractor;
import org.synanton.extraction.client.StructuredExtractionRequiredException;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.StageUsage;
import org.synanton.synflux.document.DocumentStructure;
import org.synanton.synflux.document.DocumentStructureService;
import org.synanton.synflux.document.SourceDocument;
import org.synanton.synflux.document.UnsupportedDocumentFormatException;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.StageUsageTracker;
import org.synanton.synvault.port.ObjectStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.ExtractionOptions;
import org.synanton.extraction.v1.ExtractionRequestItem;
import org.synanton.extraction.v1.ExtractionResult;
import org.synanton.extraction.v1.ExtractionStatus;
import org.synanton.extraction.v1.ObjectReference;
import org.synanton.extraction.v1.SubmitExtractionRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExtractionStage implements PipelineStage<AcquiredDocument, ParsedDocument> {

    private static final Logger log = LoggerFactory.getLogger(ExtractionStage.class);

    private final ExtractionPlaneClient extractionClient;
    private final LocalTikaFallbackExtractor fallbackExtractor;
    private final ExtractionFallbackPolicy fallbackPolicy;
    private final ExtractionClientMetrics metrics;
    private final ObjectStorePort objectStore;
    private final String hotBucket;
    private final DocumentStructureService structureService;

    public ExtractionStage(
            ExtractionPlaneClient extractionClient,
            LocalTikaFallbackExtractor fallbackExtractor,
            ExtractionFallbackPolicy fallbackPolicy,
            ExtractionClientMetrics metrics,
            ObjectStorePort objectStore,
            String hotBucket) {
        this(extractionClient, fallbackExtractor, fallbackPolicy, metrics, objectStore, hotBucket,
            DocumentStructureService.defaults());
    }

    public ExtractionStage(
            ExtractionPlaneClient extractionClient,
            LocalTikaFallbackExtractor fallbackExtractor,
            ExtractionFallbackPolicy fallbackPolicy,
            ExtractionClientMetrics metrics,
            ObjectStorePort objectStore,
            String hotBucket,
            DocumentStructureService structureService) {
        this.extractionClient = extractionClient;
        this.fallbackExtractor = fallbackExtractor;
        this.fallbackPolicy = fallbackPolicy;
        this.metrics = metrics;
        this.objectStore = objectStore;
        this.hotBucket = hotBucket;
        this.structureService = structureService;
    }

    @Override
    public String name() { return "extraction"; }

    @Override
    public ParsedDocument apply(AcquiredDocument doc, StageContext ctx) {
        long inputBytes = doc.bytes().length;
        StageUsageTracker.TimedResult<ParsedDocument> timed = StageUsageTracker.time(() -> extract(doc, ctx));
        ParsedDocument parsed = timed.value();
        long outputChars = parsed == null || parsed.text() == null ? 0 : parsed.text().length();
        ctx.usage().record(new StageUsage(
            name(), timed.wallMs(), timed.cpuNs(), null,
            0, outputChars, 0, 0, inputBytes, 0));
        return parsed;
    }

    private ParsedDocument extract(AcquiredDocument doc, StageContext ctx) {
        DocumentStructure structure = parseStructure(doc, ctx);
        if (extractionClient.isEnabled()) {
            try {
                ExtractionResult result = extractionClient.extract(buildRequest(doc, ctx));
                ParsedDocument parsed = toParsedDocument(doc, result);
                if (parsed != null) {
                    return attachStructure(parsed, structure);
                }
                log.warn("Structured extraction returned no payload for ref={}", doc.contentRefId());
                if (fallbackPolicy == ExtractionFallbackPolicy.STRUCTURED_REQUIRED) {
                    throw new StructuredExtractionRequiredException(
                        "Structured extraction returned no usable payload for ref=" + doc.contentRefId());
                }
            } catch (StructuredExtractionRequiredException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Structured extraction failed for ref={}, policy={}: {}",
                        doc.contentRefId(), fallbackPolicy, e.getMessage());
                if (fallbackPolicy == ExtractionFallbackPolicy.STRUCTURED_REQUIRED) {
                    throw new StructuredExtractionRequiredException(
                            "Structured extraction required but plane call failed for ref="
                                    + doc.contentRefId(), e);
                }
            }
        } else if (fallbackPolicy == ExtractionFallbackPolicy.STRUCTURED_REQUIRED) {
            metrics.recordFallback("client_disabled");
            throw new StructuredExtractionRequiredException(
                    "Structured extraction required but extraction client is disabled for ref="
                            + doc.contentRefId());
        }

        return attachStructure(applyFallback(doc, "plane_unavailable_or_empty"), structure);
    }

    private DocumentStructure parseStructure(AcquiredDocument doc, StageContext ctx) {
        try {
            String sourceVersionId = ctx.sourceVersionId() != null
                ? ctx.sourceVersionId().toString()
                : doc.contentRefId().toString();
            String sourceId = doc.sourceUri() == null || doc.sourceUri().isBlank()
                ? doc.ref().uri()
                : doc.sourceUri();
            return structureService.process(new SourceDocument(
                ctx.tenant(), sourceId, sourceVersionId, doc.contentRefId().toString(),
                doc.mimeType(), doc.bytes()));
        } catch (UnsupportedDocumentFormatException e) {
            return null;
        }
    }

    private ParsedDocument attachStructure(ParsedDocument parsed, DocumentStructure structure) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("structural_contract_version", "R0.2");
        if (structure == null) {
            metadata.put("structural_status", "UNSUPPORTED");
            return parsed.withStructure(null, metadata);
        }
        metadata.put("structural_status", "PARSED");
        metadata.put("structural_document_id", structure.documentId());
        metadata.put("structural_source_id", structure.sourceId());
        metadata.put("structural_source_version_id", structure.sourceVersionId());
        metadata.put("structural_media_type", structure.mediaType());
        metadata.put("structural_normalization_version", structure.normalizationVersion());
        metadata.put("structural_chunking_config", structure.chunkingConfig());
        metadata.put("structural_text_sha256", sha256(structure.text()));
        metadata.put("extraction_text_sha256", sha256(parsed.text()));
        metadata.put("text_projection", "EXTRACTION_TEXT");
        metadata.put("structural_parser_name", structure.parserName());
        metadata.put("structural_parser_version", structure.parserVersion());
        metadata.put("structural_element_count", Integer.toString(structure.elements().size()));
        metadata.put("structural_chunk_count", Integer.toString(structure.chunks().size()));
        return parsed.withStructure(structure, metadata);
    }

    private ParsedDocument toParsedDocument(AcquiredDocument doc, ExtractionResult result) {
        ExtractionStatus status = result.getStatus();
        if (status != ExtractionStatus.STATUS_COMPLETED && status != ExtractionStatus.STATUS_PARTIAL) {
            return null;
        }
        if (!result.hasPayload() || !result.getPayload().hasInlineContent()) {
            return null;
        }
        try {
            DocumentPayload documentPayload = DocumentPayload.parseFrom(result.getPayload().getInlineContent());
            String flatText = !result.getFlattenedText().isBlank()
                    ? result.getFlattenedText()
                    : documentPayload.getFlattenedText();
            Map<String, String> metadata = new HashMap<>(documentPayload.getMetadataMap());
            Map<String, String> featureStates = new java.util.LinkedHashMap<>();
            result.getFeatureStatesMap().forEach((name, state) -> featureStates.put(name, state.name()));
            Map<String, String> payloadDescriptor = new java.util.LinkedHashMap<>();
            if (result.getPayload().hasPayloadDescriptor()) {
                var descriptor = result.getPayload().getPayloadDescriptor();
                payloadDescriptor.put("schema_id", descriptor.getSchemaId());
                payloadDescriptor.put("schema_version", descriptor.getSchemaVersion());
                payloadDescriptor.put("processor_id", descriptor.getProcessorId());
                payloadDescriptor.put("processor_version", descriptor.getProcessorVersion());
            }
            return new ParsedDocument(doc, flatText, metadata, documentPayload,
                    featureStates, payloadDescriptor);
        } catch (Exception e) {
            log.warn("Failed to parse extraction payload for ref={}: {}", doc.contentRefId(), e.getMessage());
            return null;
        }
    }

    private ParsedDocument applyFallback(AcquiredDocument doc, String reason) {
        metrics.recordFallback(reason);
        log.warn("Using extraction fallback ({}) for ref={}", fallbackPolicy, doc.contentRefId());
        boolean includeMetadata = fallbackPolicy != ExtractionFallbackPolicy.FAIL_OPEN_TEXT_ONLY;
        FallbackExtractionResult fallback = fallbackExtractor.extract(
                doc.bytes(), doc.sourceUri(), includeMetadata);
        return new ParsedDocument(doc, fallback.flatText(), fallback.metadata(), null);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private SubmitExtractionRequest buildRequest(AcquiredDocument doc, StageContext ctx) {
        String key = ctx.tenant() + "/" + doc.contentRefId();
        objectStore.putObject(
                hotBucket, key,
                new ByteArrayInputStream(doc.bytes()),
                doc.bytes().length,
                doc.mimeType()
        );

        ObjectReference source = ObjectReference.newBuilder()
                .setBucket(hotBucket)
                .setKey(key)
                .setSha256(doc.sha256())
                .setSizeBytes(doc.bytes().length)
                .build();

        ExtractionRequestItem item = ExtractionRequestItem.newBuilder()
                .setContentRefId(doc.contentRefId().toString())
                .setSource(source)
                .setMediaType(doc.mimeType())
                .setOptions(ExtractionOptions.newBuilder()
                        .setLayout(true)
                        .setTables(true)
                        .setEmbeddedImages(true)
                        .build())
                .build();

        return SubmitExtractionRequest.newBuilder()
                .setTenantId(ctx.tenant())
                .setIdempotencyKey("synflux-" + doc.contentRefId() + "-v1")
                .setItem(item)
                .setPriorityClass(extractionClient.defaultPriorityClass())
                .build();
    }
}
