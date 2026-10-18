package org.synanton.synquest.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Replaceable-backend port for the #255 search projection and query path
 * (Design 1.31: search indexes are derived projections of canonical knowledge,
 * rebuildable from the canonical store — never authoritative).
 *
 * <p>Implementations project canonical knowledge chunks into a retrieval
 * backend and generate tenant-scoped candidates. Tenant eligibility is applied
 * during candidate generation — every call is scoped to the verified tenant —
 * so ranking never sees another tenant's content.
 */
public interface KnowledgeSearchBackend {

    /** Retrieval strategy name surfaced in the public search metadata. */
    String strategy();

    /** True when the tenant's projection is built and searchable. */
    boolean isReady(String tenantId);

    /**
     * Observable projection generation (the retrieval backend's commit
     * generation) — changes when the projection is rebuilt, letting clients
     * detect that results may have changed under the same query.
     */
    long generation(String tenantId);

    /**
     * Rebuilds the tenant's projection from canonical knowledge. Idempotent:
     * re-projecting the same canonical state yields the same projection, so
     * duplicate or out-of-order projection triggers converge.
     *
     * @return the number of chunks projected
     */
    int project(String tenantId) throws Exception;

    /**
     * Generates tenant-scoped candidates for the query, before ranking. Every
     * candidate carries its stable canonical references (chunk and source
     * versions) for the public result mapping.
     */
    List<KnowledgeCandidate> candidates(String tenantId, String query, int limit) throws Exception;

    /** One candidate: stable canonical references plus ranking and display fields. */
    record KnowledgeCandidate(
        String objectId,
        UUID contentRefId,
        int chunkOrdinal,
        double score,
        String title,
        String snippet,
        String sourceUri,
        Set<UUID> sourceVersionIds
    ) {
        public String provenanceRef() {
            return "/v1/knowledge/" + objectId + "/provenance";
        }
    }
}
