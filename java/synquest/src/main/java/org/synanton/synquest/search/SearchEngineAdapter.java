package org.synanton.synquest.search;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;
import org.synanton.synquest.api.dto.IndexStats;

/**
 * Combined search-engine port (#550): projection writes/lifecycle plus the
 * read path. Implementations hide the physical engine (embedded Lucene today,
 * remote Solr/Elasticsearch later, ADR-021). No engine types cross this boundary.
 */
public interface SearchEngineAdapter extends SearchIndexAdapter {

    void initTenant(String tenant);

    void reindex(String tenant) throws IOException;

    void projectContentRefs(String tenant, Collection<UUID> contentRefs) throws IOException;

    SearchResult search(SearchQuery query, SearchFilter filter) throws IOException;

    IndexStats stats(String tenant) throws IOException;

    boolean isReady(String tenant);

    String statusName();
}
