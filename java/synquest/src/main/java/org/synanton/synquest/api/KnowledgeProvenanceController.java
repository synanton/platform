package org.synanton.synquest.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.synanton.common.tenant.TenantContext;
import org.synanton.storage.contract.TenantScope;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the provenance reference carried by every search hit
 * (provenance_ref = /v1/knowledge/{object_id}/provenance) to the canonical
 * references behind the chunk: the source versions it derives from, the
 * Content Cache artifact (two-path resolution), and the processing run that
 * produced it.
 *
 * <p>object_id is the canonical chunk id {content_ref}:{ordinal} — stable
 * because chunks are content-addressed (identical bytes derive the same
 * chunk ordinal). The reference resolves only while the producing run is
 * COMPLETED (manifest visibility, #295); otherwise 404 — a provenance
 * reference never resolves to invisible knowledge.
 */
@RestController
public class KnowledgeProvenanceController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProvenanceController.class);

    private final IngestionCacheClient cacheClient;

    public KnowledgeProvenanceController(IngestionCacheClient cacheClient) {
        this.cacheClient = cacheClient;
    }

    @GetMapping("/v1/knowledge/{objectId}/provenance")
    public ResponseEntity<KnowledgeProvenanceResource> provenance(@PathVariable String objectId) {
        TenantScope tenant = verifiedScope();
        ParsedObjectId parsed;
        try {
            parsed = ParsedObjectId.parse(objectId);
        } catch (IllegalArgumentException malformed) {
            return ResponseEntity.badRequest().build();
        }
        var manifest = cacheClient.readManifest(tenant.tenantId(), parsed.contentRefId());
        if (manifest.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var chunk = cacheClient.readChunks(tenant.tenantId(), parsed.contentRefId()).stream()
                .filter(c -> c.chunkOrdinal() == parsed.ordinal())
                .findFirst();
        if (chunk.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Set<UUID> tombstoned = cacheClient.readTombstonedVersionIds(tenant.tenantId());
        Set<UUID> deletedVersions = tombstoned == null ? Set.of() : tombstoned;
        List<UUID> sourceVersionIds = chunk.get().sourceVersionIds() == null
                ? List.of()
                : chunk.get().sourceVersionIds().stream()
                    .filter(versionId -> !deletedVersions.contains(versionId))
                    .sorted()
                    .toList();
        if (sourceVersionIds.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<LiveSourceIdentity> identities = liveIdentities(
                tenant.tenantId(), sourceVersionIds, manifest.get().sourceUri());
        return ResponseEntity.ok(new KnowledgeProvenanceResource(
                objectId,
                parsed.contentRefId(),
                sourceVersionIds,
                identities,
                manifest.get().contentCacheReference(),
                manifest.get().sourceUri(),
                manifest.get().sourceSha256(),
                chunk.get().chunkSha256(),
                chunk.get().processingRunId()));
    }

    private List<LiveSourceIdentity> liveIdentities(
            String tenantId, List<UUID> sourceVersionIds, String fallbackSourceUri) {
        Set<UUID> live = Set.copyOf(sourceVersionIds);
        List<LiveSourceIdentity> identities = new ArrayList<>();
        for (SourceRow source : cacheClient.listSources(tenantId)) {
            for (SourceIdentityRow identity : cacheClient.listSourceIdentities(
                    tenantId, source.sourceId(), source.connectorId())) {
                for (SourceVersionRow version : cacheClient.listSourceVersions(
                        tenantId, identity.connectorId(), identity.sourceId(), identity.resourceId())) {
                    if (!live.contains(version.versionId())) {
                        continue;
                    }
                    String sourceUri = "upload".equals(source.connectorType())
                        ? identity.connectorId() + "://" + identity.sourceId() + "/" + identity.resourceId()
                        : fallbackSourceUri;
                    identities.add(new LiveSourceIdentity(
                        version.versionId(), identity.connectorId(), identity.sourceId(),
                        identity.resourceId(), sourceUri));
                }
            }
        }
        identities.sort(Comparator.comparing(LiveSourceIdentity::sourceVersionId));
        return identities;
    }

    private static TenantScope verifiedScope() {
        TenantContext context = TenantContext.get();
        if (context == null) {
            throw new IllegalStateException("Verified tenant context is required");
        }
        return TenantScope.of(context.requireTenantId());
    }

    /**
     * Canonical chunk id: {content_ref}:{ordinal}, ordinal a non-negative int.
     * The separator is ':' (RFC 3986 pchar, absent from UUIDs) so the id is
     * URL-safe inside provenance_ref without client-side encoding.
     */
    record ParsedObjectId(UUID contentRefId, int ordinal) {
        static ParsedObjectId parse(String objectId) {
            int sep = objectId == null ? -1 : objectId.lastIndexOf(':');
            if (sep <= 0 || sep == objectId.length() - 1) {
                throw new IllegalArgumentException("object_id must be {content_ref}:{ordinal}");
            }
            try {
                int ordinal = Integer.parseInt(objectId.substring(sep + 1));
                if (ordinal < 0) {
                    throw new NumberFormatException();
                }
                return new ParsedObjectId(UUID.fromString(objectId.substring(0, sep)), ordinal);
            } catch (RuntimeException bad) {
                throw new IllegalArgumentException("object_id must be {content_ref}:{ordinal}", bad);
            }
        }
    }

    public record KnowledgeProvenanceResource(
            @JsonProperty("object_id") String objectId,
            @JsonProperty("content_ref_id") UUID contentRefId,
            @JsonProperty("source_version_ids") List<UUID> sourceVersionIds,
            @JsonProperty("source_identities") List<LiveSourceIdentity> sourceIdentities,
            @JsonProperty("content_cache_reference") String contentCacheReference,
            @JsonProperty("source_uri") String sourceUri,
            @JsonProperty("source_sha256") String sourceSha256,
            @JsonProperty("chunk_sha256") String chunkSha256,
            @JsonProperty("processing_run_id") UUID processingRunId) {}

    public record LiveSourceIdentity(
            @JsonProperty("source_version_id") UUID sourceVersionId,
            @JsonProperty("connector_id") String connectorId,
            @JsonProperty("source_id") String sourceId,
            @JsonProperty("resource_id") String resourceId,
            @JsonProperty("source_uri") String sourceUri) {}
}
