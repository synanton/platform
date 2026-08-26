package org.synanton.synflux.pipeline;

import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.domain.UsageAccumulator;

public record StageContext(
    String tenant,
    String jobId,
    SynfluxProperties props,
    UsageAccumulator usage
) {
    public StageContext(String tenant, String jobId, SynfluxProperties props) {
        this(tenant, jobId, props, new UsageAccumulator());
    }
}
