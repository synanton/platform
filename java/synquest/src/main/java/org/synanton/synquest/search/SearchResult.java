package org.synanton.synquest.search;

import java.util.List;
import org.synanton.synquest.api.dto.QueryUsage;
import org.synanton.synquest.api.dto.SearchTrace;

/** Engine-neutral ranked result (#550). */
public record SearchResult(
        List<SearchDocument> documents,
        SearchTrace trace,
        QueryUsage queryUsage) {

    /** Generation of the Lucene searcher used for this result; null only for an empty trace. */
    public String searchGeneration() {
        return trace == null ? null : Long.toString(trace.indexGeneration());
    }
}
