package org.synanton.gateway.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryResponse(
        List<Hit> hits,
        GraphResult graphResult,
        String answer,
        ExecutionTrace executionTrace,
        boolean cacheHit,
        List<String> aclLayersApplied
) {
    public QueryResponse(List<Hit> hits, GraphResult graphResult, String answer, ExecutionTrace executionTrace) {
        this(hits, graphResult, answer, executionTrace, false, List.of());
    }
}
