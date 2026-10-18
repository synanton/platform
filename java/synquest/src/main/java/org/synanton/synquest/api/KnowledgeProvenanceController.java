package org.synanton.synquest.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.synanton.common.tenant.TenantContext;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
        TenantContext tenant = verifiedTenant();
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
        List<UUID> sourceVersionIds = chunk.get().sourceVersionIds() == null
                ? List.of()
                : chunk.get().sourceVersionIds().stream().sorted().toList();
        return ResponseEntity.ok(new KnowledgeProvenanceResource(
                objectId,
                parsed.contentRefId(),
                sourceVersionIds,
                manifest.get().contentCacheReference(),
                manifest.get().sourceUri(),
                manifest.get().sourceSha256(),
                chunk.get().chunkSha256(),
                chunk.get().processingRunId()));
    }

    private static TenantContext verifiedTenant() {
        TenantContext context = TenantContext.get();
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            throw new IllegalStateException("Verified tenant context is required");
        }
        return context;
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
            @JsonProperty("content_cache_reference") String contentCacheReference,
            @JsonProperty("source_uri") String sourceUri,
            @JsonProperty("source_sha256") String sourceSha256,
            @JsonProperty("chunk_sha256") String chunkSha256,
            @JsonProperty("processing_run_id") UUID processingRunId) {}
}
