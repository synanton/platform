package org.synanton.synflux.pipeline.stage;

import org.synanton.extraction.client.ExtractionPlaneClient;
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
    private final ObjectStorePort objectStore;
    private final String hotBucket;

    public ExtractionStage(
            ExtractionPlaneClient extractionClient,
            ObjectStorePort objectStore,
            String hotBucket) {
        this.extractionClient = extractionClient;
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
        if (!extractionClient.isEnabled()) {
            throw new StructuredExtractionRequiredException(
                    "Document extraction requires the Content Extractor client for ref="
                            + doc.contentRefId());
        }
        try {
            ExtractionResult result = extractionClient.extract(buildRequest(doc, ctx));
            ParsedDocument parsed = toParsedDocument(doc, result);
            if (parsed == null) {
                throw new StructuredExtractionRequiredException(
                        "Content Extractor returned no usable structured payload for ref="
                                + doc.contentRefId());
            }
            return parsed;
        } catch (StructuredExtractionRequiredException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Content Extractor call failed for ref={}: {}", doc.contentRefId(), e.getMessage());
            throw new StructuredExtractionRequiredException(
                    "Content Extractor call failed for ref=" + doc.contentRefId(), e);
        }
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
                payloadDescriptor.put("format", descriptor.getFormat().name());
                payloadDescriptor.put("schema_digest", descriptor.getSchemaDigest());
                payloadDescriptor.put("payload_digest", descriptor.getPayloadDigest());
            }
            return new ParsedDocument(doc, flatText, metadata, documentPayload,
                    featureStates, payloadDescriptor);
        } catch (Exception e) {
            log.warn("Failed to parse extraction payload for ref={}: {}", doc.contentRefId(), e.getMessage());
            return null;
        }
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
