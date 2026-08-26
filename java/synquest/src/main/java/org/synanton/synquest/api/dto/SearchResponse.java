package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SearchResponse(
        List<Hit> hits,
        SearchTrace trace,
        @JsonProperty("query_usage") QueryUsage queryUsage
) {}
