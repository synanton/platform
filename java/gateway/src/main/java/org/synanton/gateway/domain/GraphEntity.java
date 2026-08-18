package org.synanton.gateway.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphEntity(
        String id,
        String label,
        String type,
        List<SourceRef> sourceRefs
) {}
