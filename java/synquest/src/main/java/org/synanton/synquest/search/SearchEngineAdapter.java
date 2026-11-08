package org.synanton.synquest.search;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Engine-neutral write/lifecycle port for the search projection (#547).
 * Implementations hide the physical engine (embedded Lucene today; remote
 * Solr/Elasticsearch later, see ADR-020). No engine types cross this boundary.
 */
public interface SearchEngineAdapter {

    /** Full rebuild of the tenant projection from canonical knowledge. */
    void rebuild(String tenant) throws IOException;

    /** Incrementally upserts the listed canonical content refs. */
    int indexContentRefs(String tenant, Collection<UUID> contentRefs) throws IOException;

    /** Deletes chunks by their stable document ids. */
    int deleteChunkIds(String tenant, Collection<String> chunkIds) throws IOException;

    /** True when the tenant has no index yet (the first projection needs a rebuild). */
    boolean isEmpty(String tenant);

    /** Physical index location for the current embedded engine. */
    Path indexPath(String tenant);

    /** Last full-build report, when available. */
    Optional<IndexReport> lastReport(String tenant);
}
