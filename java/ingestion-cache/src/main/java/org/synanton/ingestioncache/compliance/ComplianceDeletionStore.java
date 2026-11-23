package org.synanton.ingestioncache.compliance;

import java.util.Objects;
import java.util.UUID;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.quarantine.QuarantineStore;

/**
 * #845 Phase 2a hard deletion for the currently available scope:
 * generations, quarantine records, and accepted extraction results.
 *
 * <p>Cache rows and capture/diagnostic artifacts are intentionally out of
 * scope until #829/#830 land. Dev-touched provenance is not a Cassandra target
 * and is never modified here.
 */
public final class ComplianceDeletionStore {

    public record DeletionResult(int generations, int extractionResults, boolean idempotent) {
        public int total() {
            return generations + extractionResults;
        }
    }

    private final QuarantineStore quarantine;
    private final IngestionCacheClient cache;
    private final ComplianceDeletionAuditStore audit;

    public ComplianceDeletionStore(QuarantineStore quarantine, IngestionCacheClient cache,
                                   ComplianceDeletionAuditStore audit) {
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public DeletionResult hardDeleteSource(String tenantId, UUID sourceVersionId,
                                           UUID contentRefId, String actor, String reason) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
        Objects.requireNonNull(contentRefId, "contentRefId");

        audit.record(ComplianceDeletionAuditStore.event(tenantId, sourceVersionId,
                "HARD_DELETE_ATTEMPTED", "GENERATION+QUARANTINE+EXTRACTION_RESULTS", 0,
                actor, reason, null));

        int generations = quarantine.deleteSource(tenantId, sourceVersionId,
                QuarantineStore.DeletionMode.HARD_DELETE, reason);
        int extractionResults = cache.deleteExtractionResultsForVersion(
                tenantId, contentRefId, sourceVersionId);
        boolean idempotent = generations == 0 && extractionResults == 0;

        audit.record(ComplianceDeletionAuditStore.event(tenantId, sourceVersionId,
                idempotent ? "HARD_DELETE_IDEMPOTENT" : "HARD_DELETE_APPLIED",
                "GENERATION+QUARANTINE+EXTRACTION_RESULTS",
                generations + extractionResults, actor, reason,
                "generations=" + generations + ",extractionResults=" + extractionResults));
        return new DeletionResult(generations, extractionResults, idempotent);
    }
}
