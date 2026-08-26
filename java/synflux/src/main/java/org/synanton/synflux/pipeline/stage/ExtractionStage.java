package org.synanton.synflux.pipeline.stage;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.port.ObjectStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import synanton.extraction.v1.DocumentPayload;
import synanton.extraction.v1.ExtractionOptions;
import synanton.extraction.v1.ExtractionRequestItem;
import synanton.extraction.v1.ExtractionResult;
import synanton.extraction.v1.ExtractionServiceGrpc;
import synanton.extraction.v1.ExtractionStatus;
import synanton.extraction.v1.ObjectReference;
import synanton.extraction.v1.PriorityClass;
import synanton.extraction.v1.SubmitExtractionRequest;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Replaces the legacy {@link ParseStage}.
 *
 * <p>Two-part operation:
 * <ol>
 *   <li>Upload the raw content bytes to the hot bucket so the extraction plane can read them.</li>
 *   <li>Call {@code ExtractSync} on the extraction service to get a {@link DocumentPayload} with
 *       structured elements (headings, paragraphs, tables, …).</li>
 * </ol>
 *
 * <p>Tika is kept as a fallback for flat-text extraction and for environments where the extraction
 * service is not configured. {@link ParsedDocument#documentPayload()} is {@code null} when the
 * service is unavailable or the call fails; {@link org.synanton.synflux.pipeline.stage.SemanticChunkStage}
 * detects this and falls back to token-based splitting.
 */
public class ExtractionStage implements PipelineStage<AcquiredDocument, ParsedDocument> {

    private static final Logger log = LoggerFactory.getLogger(ExtractionStage.class);
    private static final Tika TIKA = new Tika();

    private final ExtractionServiceGrpc.ExtractionServiceBlockingStub extractionStub;
    private final ObjectStorePort objectStore;
    private final String hotBucket;

    /**
     * @param extractionStub gRPC stub; {@code null} means extraction service is not configured
     * @param objectStore    used to upload raw bytes before calling the extraction service
     * @param hotBucket      MinIO/S3 bucket where raw content is staged
     */
    public ExtractionStage(
            ExtractionServiceGrpc.ExtractionServiceBlockingStub extractionStub,
            ObjectStorePort objectStore,
            String hotBucket) {
        this.extractionStub = extractionStub;
        this.objectStore    = objectStore;
        this.hotBucket      = hotBucket;
    }

    @Override
    public String name() { return "extraction"; }

    @Override
    public ParsedDocument apply(AcquiredDocument doc, StageContext ctx) {
        if (extractionStub != null) {
            try {
                ExtractionResult result = callExtractionService(doc, ctx);
                if (result != null
                        && (result.getStatus() == ExtractionStatus.STATUS_COMPLETED
                            || result.getStatus() == ExtractionStatus.STATUS_PARTIAL)
                        && result.hasPayload()
                        && result.getPayload().hasInlineContent()) {
                    DocumentPayload documentPayload = DocumentPayload.parseFrom(
                            result.getPayload().getInlineContent());
                    String flatText = !result.getFlattenedText().isBlank()
                            ? result.getFlattenedText()
                            : documentPayload.getFlattenedText();
                    Map<String, String> metadata = new HashMap<>(documentPayload.getMetadataMap());
                    return new ParsedDocument(doc, flatText, metadata, documentPayload);
                }
            } catch (Exception e) {
                log.warn("Structured extraction failed for ref={}, falling back to flat text: {}",
                    doc.contentRefId(), e.getMessage());
            }
        }

        String flatText = extractFlatText(doc);
        Map<String, String> metadata = extractMetadata(doc);
        return new ParsedDocument(doc, flatText, metadata, null);
    }

    private ExtractionResult callExtractionService(AcquiredDocument doc, StageContext ctx) {

        // Stage raw bytes in object storage so the extraction plane can read them.
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

        SubmitExtractionRequest request = SubmitExtractionRequest.newBuilder()
            .setTenantId(ctx.tenant())
            .setIdempotencyKey("synflux-" + doc.contentRefId() + "-v1")
            .setItem(item)
            .setPriorityClass(PriorityClass.PRIORITY_NORMAL)
            .build();

        ExtractionResult result = extractionStub.extractSync(request);

        ExtractionStatus status = result.getStatus();
        if (status != ExtractionStatus.STATUS_COMPLETED && status != ExtractionStatus.STATUS_PARTIAL) {
            log.warn("Extraction returned status {} for ref={}", status, doc.contentRefId());
            return null;
        }

        if (!result.hasPayload() || !result.getPayload().hasInlineContent()) {
            log.warn("Extraction returned no inline payload for ref={}", doc.contentRefId());
            return null;
        }

        return result;
    }

    // ─── Flat-text fallback (Tika) ────────────────────────────────────────────

    private static String extractFlatText(AcquiredDocument doc) {
        try {
            return TIKA.parseToString(new ByteArrayInputStream(doc.bytes()));
        } catch (Exception e) {
            log.warn("Flat text extraction failed for {}: {}", doc.sourceUri(), e.getMessage());
            return "";
        }
    }

    private static Map<String, String> extractMetadata(AcquiredDocument doc) {
        Map<String, String> meta = new HashMap<>();
        try {
            Metadata tikaMetadata = new Metadata();
            TIKA.parseToString(new ByteArrayInputStream(doc.bytes()), tikaMetadata);
            for (String name : tikaMetadata.names()) {
                meta.put(name, tikaMetadata.get(name));
            }
        } catch (Exception e) {
            log.warn("Metadata extraction failed for {}: {}", doc.sourceUri(), e.getMessage());
        }
        return meta;
    }
}
