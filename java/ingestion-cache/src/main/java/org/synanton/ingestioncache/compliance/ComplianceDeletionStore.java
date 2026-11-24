package org.synanton.ingestioncache.compliance;

import java.util.Objects;
import java.util.UUID;
import org.synanton.ingestioncache.cache.ExtractionCacheStore;
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

    public record DeletionResult(int generations, int extractionResults, int cacheEntries,
                                 int captureArtifacts, boolean idempotent) {
        public int total() {
            return generations + extractionResults + cacheEntries + captureArtifacts;
        }
    }

    private final QuarantineStore quarantine;
    private final IngestionCacheClient cache;
    private final ExtractionCacheStore extractionCache;
    private final CaptureCleanup captureCleanup;
    private final ComplianceDeletionAuditStore audit;

    public ComplianceDeletionStore(QuarantineStore quarantine, IngestionCacheClient cache,
                                   ComplianceDeletionAuditStore audit) {
        this(quarantine, cache, null, null, audit);
    }

    public ComplianceDeletionStore(QuarantineStore quarantine, IngestionCacheClient cache,
                                   ExtractionCacheStore extractionCache,
                                   CaptureCleanup captureCleanup,
                                   ComplianceDeletionAuditStore audit) {
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.extractionCache = extractionCache;
        this.captureCleanup = captureCleanup;
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public DeletionResult hardDeleteSource(String tenantId, UUID sourceVersionId,
                                           UUID contentRefId, String actor, String reason) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
        Objects.requireNonNull(contentRefId, "contentRefId");

        String scope = "GENERATION+QUARANTINE+EXTRACTION_RESULTS"
                + (extractionCache == null ? "" : "+EXTRACTION_CACHE")
                + (captureCleanup == null ? "" : "+CAPTURE");
        audit.record(ComplianceDeletionAuditStore.event(tenantId, sourceVersionId,
                "HARD_DELETE_ATTEMPTED", scope, 0, actor, reason, null));

        int generations = quarantine.deleteSource(tenantId, sourceVersionId,
                QuarantineStore.DeletionMode.HARD_DELETE, reason);
        int extractionResults = cache.deleteExtractionResultsForVersion(
                tenantId, contentRefId, sourceVersionId);
        int cacheEntries = extractionCache == null ? 0
                : extractionCache.deleteForSource(tenantId, sourceVersionId);
        int captureArtifacts = captureCleanup == null ? 0
                : captureCleanup.deleteCapture(tenantId, sourceVersionId);
        boolean idempotent = generations == 0 && extractionResults == 0 && cacheEntries == 0
                && captureArtifacts == 0;

        audit.record(ComplianceDeletionAuditStore.event(tenantId, sourceVersionId,
                idempotent ? "HARD_DELETE_IDEMPOTENT" : "HARD_DELETE_APPLIED",
                scope, generations + extractionResults + cacheEntries + captureArtifacts,
                actor, reason,
                "generations=" + generations + ",extractionResults=" + extractionResults
                        + ",cacheEntries=" + cacheEntries
                        + ",captureArtifacts=" + captureArtifacts));
        return new DeletionResult(generations, extractionResults, cacheEntries,
                captureArtifacts, idempotent);
    }
}
