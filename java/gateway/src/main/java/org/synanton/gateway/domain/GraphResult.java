package org.synanton.gateway.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphResult(
        List<GraphEntity> entities,
        List<GraphEdge> edges,
        List<Object> paths
) {
    public static GraphResult empty() {
        return new GraphResult(List.of(), List.of(), List.of());
    }
}
