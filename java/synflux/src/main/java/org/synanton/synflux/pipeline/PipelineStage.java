package org.synanton.synflux.pipeline;

public interface PipelineStage<In, Out> {
    String name();
    Out apply(In in, StageContext ctx);
}
