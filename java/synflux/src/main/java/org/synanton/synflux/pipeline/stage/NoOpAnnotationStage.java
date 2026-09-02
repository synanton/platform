package org.synanton.synflux.pipeline.stage;

import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.pipeline.PipelineStage;
import org.synanton.synflux.pipeline.StageContext;

public class NoOpAnnotationStage implements PipelineStage<ChunkedDocument, ChunkedDocument> {
    @Override
    public String name() {
        return "annotation-noop";
    }

    @Override
    public ChunkedDocument apply(ChunkedDocument in, StageContext ctx) {
        return in;
    }
}
