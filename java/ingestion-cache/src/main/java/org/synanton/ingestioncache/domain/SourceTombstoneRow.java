package org.synanton.ingestioncache.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable, provenance-bearing record of an authoritative source deletion. */
public record SourceTombstoneRow(
    String tenantId,
    String connectorId,
    String sourceId,
    String resourceId,
    UUID tombstoneId,
    Instant deletedAt,
    String deletedByPrincipalId,
    String deletedByPrincipalType,
    int versionCount,
    UUID versionsManifestRef,
    UUID nextIncarnationId
) {
    /** Timestamp fallback for consumers that cannot resolve the tombstone's version manifest. */
    public boolean appliesTo(SourceVersionRow version) {
        return tenantId.equals(version.tenantId())
            && connectorId.equals(version.connectorId())
            && sourceId.equals(version.sourceId())
            && resourceId.equals(version.resourceId())
            && !version.observedAt().isAfter(deletedAt);
    }
}
