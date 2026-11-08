package org.synanton.synquest.search;

import java.util.List;
import org.synanton.synquest.api.dto.QueryUsage;
import org.synanton.synquest.api.dto.SearchTrace;

/** Engine-neutral ranked result (#550). */
public record SearchResult(
        List<SearchDocument> documents,
        SearchTrace trace,
        QueryUsage queryUsage) {}
