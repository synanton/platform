package org.synanton.synflux.pipeline.stage;

import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;

public class NoOpEnrichmentStage implements PipelineStage<ChunkedDocument, ChunkedDocument> {
    @Override public String name() { return "enrich-noop"; }
    @Override public ChunkedDocument apply(ChunkedDocument doc, StageContext ctx) { return doc; }
}
