package org.synanton.synflux.pipeline;

import org.synanton.synflux.config.SynfluxProperties;

public record StageContext(
    String tenant,
    String jobId,
    SynfluxProperties props
) {}
