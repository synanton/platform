package org.synanton.gateway.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SynthesisTrace(
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String outcome
) {}
