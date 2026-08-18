package org.synanton.synquest.api.dto;

import java.util.List;

public record SearchResponse(
        List<Hit> hits,
        SearchTrace trace
) {}
