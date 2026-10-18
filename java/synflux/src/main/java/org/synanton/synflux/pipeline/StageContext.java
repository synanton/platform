package org.synanton.synflux.pipeline;

import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.domain.UsageAccumulator;

import java.util.UUID;

public record StageContext(
    String tenant,
    String jobId,
    SynfluxProperties props,
    UsageAccumulator usage,
    UUID sourceVersionId
) {
    public StageContext(String tenant, String jobId, SynfluxProperties props) {
        this(tenant, jobId, props, new UsageAccumulator(), null);
    }

    public StageContext(String tenant, String jobId, SynfluxProperties props, UsageAccumulator usage) {
        this(tenant, jobId, props, usage, null);
    }

    /** Binds the knowledge-persist stage to the SourceVersion being published. */
    public StageContext withSourceVersionId(UUID sourceVersionId) {
        return new StageContext(tenant, jobId, props, usage, sourceVersionId);
    }
}
