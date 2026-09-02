package org.synanton.synflux.pipeline.stage;

import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.AnnotationRow;
import org.synanton.synflux.annotation.AnnotationRule;
import org.synanton.synflux.annotation.AnnotationsServiceClient;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Produces first-class annotations from chunked content (design §6-§7, AAP-1).
 *
 * <p>Every chunk this stage reads carries whatever representation the current pipeline
 * has settled on before this point ({@code SemanticChunk#content()}). Once v1.23's masking
 * stage (SEC-4) exists, this stage MUST run after it and read only the masked/authorized
 * representation, per design Invariant 5 ("Analytics cannot bypass masking or
 * representation rules") - annotation and analytics share the same rule. Until then,
 * {@code representationUsed} is recorded honestly as {@code "single"} because no
 * masked/original split exists yet in the pipeline.
 */
public class AnnotationStage implements PipelineStage<ChunkedDocument, ChunkedDocument> {

    private static final Logger log = LoggerFactory.getLogger(AnnotationStage.class);
    private static final String UNCLASSIFIED_REPRESENTATION = "single";

    private final IngestionCacheClient cacheClient;
    private final AnnotationsServiceClient annotationsClient;
    private final List<AnnotationRule> rules;
    private final String producer;
    private final String producerVersion;

    public AnnotationStage(
            IngestionCacheClient cacheClient,
            AnnotationsServiceClient annotationsClient,
            List<AnnotationRule> rules,
            String producer,
            String producerVersion
    ) {
        this.cacheClient = cacheClient;
        this.annotationsClient = annotationsClient;
        this.rules = rules;
        this.producer = producer;
        this.producerVersion = producerVersion;
    }

    @Override
    public String name() {
        return "annotation";
    }

    @Override
    public ChunkedDocument apply(ChunkedDocument doc, StageContext ctx) {
        UUID contentRefId = doc.parsed().acquired().contentRefId();
        UUID processingRunId = annotationsClient.startProcessingRun(
                producer, producerVersion, ctx.tenant(), null, null, contentRefId.toString());

        List<AnnotationRow> rows = new ArrayList<>();
        String errorSummary = null;
        try {
            for (SemanticChunk chunk : doc.chunks()) {
                for (AnnotationRule rule : rules) {
                    rule.match(chunk).ifPresent(match ->
                            rows.add(toRow(ctx.tenant(), contentRefId, chunk, rule, match, processingRunId)));
                }
            }
            if (!rows.isEmpty()) {
                cacheClient.insertAnnotations(rows);
            }
        } catch (RuntimeException e) {
            errorSummary = e.getMessage();
            throw e;
        } finally {
            annotationsClient.completeProcessingRun(
                    processingRunId, errorSummary == null ? "SUCCEEDED" : "FAILED", errorSummary);
        }

        log.info("Annotated ref={} chunks={} annotations={}", contentRefId, doc.chunks().size(), rows.size());
        return doc;
    }

    private static AnnotationRow toRow(
            String tenantId, UUID contentRefId, SemanticChunk chunk,
            AnnotationRule rule, AnnotationRule.Match match, UUID processingRunId
    ) {
        List<String> classification = chunk.classification() == null ? SemanticChunk.PUBLIC_ONLY : chunk.classification();
        return new AnnotationRow(
                tenantId, "chunk", contentRefId + ":" + chunk.ordinal(), UUID.randomUUID(),
                rule.definitionId(), rule.definitionVersion(), rule.annotationType(), rule.namespace(), rule.name(),
                match.value(), rule.producer(), rule.producerVersion(), match.confidence(),
                classification, UNCLASSIFIED_REPRESENTATION,
                "chunk:" + contentRefId + "#" + chunk.ordinal(), processingRunId, Instant.now(), null
        );
    }
}
