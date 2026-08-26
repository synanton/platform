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
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synflux.pipeline.StageUsageTracker;
import org.synanton.synvault.port.ObjectStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import synanton.extraction.v1.DocumentPayload;
import synanton.extraction.v1.ExtractionOptions;
import synanton.extraction.v1.ExtractionRequestItem;
import synanton.extraction.v1.ExtractionResult;
import synanton.extraction.v1.ExtractionStatus;
import synanton.extraction.v1.ObjectReference;
import synanton.extraction.v1.SubmitExtractionRequest;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

public class ExtractionStage implements PipelineStage<AcquiredDocument, ParsedDocument> {

    private static final Logger log = LoggerFactory.getLogger(ExtractionStage.class);

    private final ExtractionPlaneClient extractionClient;
    private final LocalTikaFallbackExtractor fallbackExtractor;
    private final ExtractionFallbackPolicy fallbackPolicy;
    private final ExtractionClientMetrics metrics;
    private final ObjectStorePort objectStore;
    private final String hotBucket;

    public ExtractionStage(
            ExtractionPlaneClient extractionClient,
            LocalTikaFallbackExtractor fallbackExtractor,
            ExtractionFallbackPolicy fallbackPolicy,
            ExtractionClientMetrics metrics,
            ObjectStorePort objectStore,
            String hotBucket) {
        this.extractionClient = extractionClient;
        this.fallbackExtractor = fallbackExtractor;
        this.fallbackPolicy = fallbackPolicy;
        this.metrics = metrics;
        this.objectStore = objectStore;
        this.hotBucket = hotBucket;
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
        if (extractionClient.isEnabled()) {
            try {
                ExtractionResult result = extractionClient.extract(buildRequest(doc, ctx));
                ParsedDocument parsed = toParsedDocument(doc, result);
                if (parsed != null) {
                    return parsed;
                }
                log.warn("Structured extraction returned no payload for ref={}", doc.contentRefId());
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

        return applyFallback(doc, "plane_unavailable_or_empty");
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
            return new ParsedDocument(doc, flatText, metadata, documentPayload);
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
