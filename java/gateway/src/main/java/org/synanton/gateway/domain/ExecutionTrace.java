package org.synanton.gateway.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionTrace(
        Object plan,
        List<StepTrace> steps,
        SynthesisTrace synthesis,
        long totalMs,
        List<String> warnings
) {}
