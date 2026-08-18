package org.synanton.gateway.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphEdge(
        String source,
        String target,
        String relation,
        List<SourceRef> sourceRefs
) {}
