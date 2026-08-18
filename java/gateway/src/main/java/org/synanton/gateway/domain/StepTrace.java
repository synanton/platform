package org.synanton.gateway.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StepTrace(
        String stepId,
        String engine,
        long startedMs,
        long durationMs,
        StepOutcome outcome,
        String error
) {}
