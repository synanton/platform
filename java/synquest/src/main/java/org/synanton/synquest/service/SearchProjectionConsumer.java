package org.synanton.synquest.service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.client.TenantPolling;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceTombstoneRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;

/**
 * Projects newly-ingested source versions into the tenant search index.
 *
 * <p>synflux writes a {@code SourcePublished} event into the {@code source_versions}
 * outbox row in the same LWT as the canonical write, but nothing consumed it — the
 * Lucene projection was only rebuilt by the manual {@code /reindex} endpoint or on
 * boot. This poller closes that gap: when due outbox events exist, it rebuilds the
 * tenant projection and marks the events published, so an upload becomes searchable
 * without an operator running a reindex by hand.
 */
@Component
public class SearchProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(SearchProjectionConsumer.class);

    private final IngestionCacheClient cacheClient;
    private final SearchService searchService;
    private final String tenant;
    private final int batchLimit;
    private final boolean projectionEnabled;

    public SearchProjectionConsumer(
            IngestionCacheClient cacheClient,
            SearchService searchService,
            @Value("${synquest.projection.tenant:*}") String tenant,
            @Value("${synquest.projection.batch-limit:200}") int batchLimit,
            @Value("${synquest.projection.enabled:true}") boolean projectionEnabled) {
        this.cacheClient = cacheClient;
        this.searchService = searchService;
        this.tenant = tenant;
        this.batchLimit = batchLimit;
        this.projectionEnabled = projectionEnabled;
    }

    /**
     * Registry mode is explicit: blank or {@code *} serves every registered
     * tenant. A concrete tenant keeps the original single-tenant behaviour.
     */
    @PostConstruct
    public void validateProjectionConfig() {
        if (!projectionEnabled) {
            log.warn("Search projection consumer is DISABLED; the search index will not update automatically");
            return;
        }
        if (tenant == null || tenant.isBlank() || TenantPolling.ALL.equals(tenant.trim())) {
            log.info("Search projection consumer active; polling tenants from the registry");
            return;
        }
        log.info("Search projection consumer active for tenant '{}'", tenant);
    }

    @Scheduled(fixedDelayString = "${synquest.projection.poll-delay-ms:5000}")
    public void projectNewSourceVersions() {
        if (!projectionEnabled) {
            return;
        }
        List<String> tenants;
        try {
            tenants = TenantPolling.resolve(tenant, cacheClient);
        } catch (Exception failure) {
            log.warn("Search projection tenant discovery failed; events stay unpublished for retry", failure);
            return;
        }
        for (String currentTenant : tenants) {
            projectTenant(currentTenant);
        }
    }

    private void projectTenant(String tenant) {
        try {
            List<SourceOutboxRow> pending =
                    cacheClient.listUnpublishedSourceOutbox(tenant, batchLimit, Instant.now());
            if (pending.isEmpty()) {
                return;
            }
            boolean fullRebuild = false;
            Set<UUID> upserts = new LinkedHashSet<>();
            Set<UUID> deletes = new LinkedHashSet<>();
            for (SourceOutboxRow event : pending) {
                if ("SourcePublished".equals(event.eventType())) {
                    UUID contentRef = resolveContentRef(tenant, event);
                    if (contentRef == null) {
                        fullRebuild = true;
                        break;
                    }
                    upserts.add(contentRef);
                } else if ("SourceDeleted".equals(event.eventType())) {
                    Set<UUID> deletedRefs = resolveDeletedContentRefs(tenant, event);
                    if (deletedRefs == null) {
                        fullRebuild = true;
                        break;
                    }
                    deletes.addAll(deletedRefs);
                } else {
                    fullRebuild = true;
                    break;
                }
            }

            if (fullRebuild) {
                searchService.reindex(tenant);
            } else {
                if (!upserts.isEmpty()) {
                    searchService.projectContentRefs(tenant, upserts);
                }
                if (!deletes.isEmpty()) {
                    searchService.deleteContentRefs(tenant, deletes);
                }
            }

            Instant publishedAt = Instant.now();
            for (SourceOutboxRow event : pending) {
                cacheClient.markSourceOutboxPublished(event, publishedAt);
                if (event.sourceVersionId() != null) {
                    // RAKG readiness: extraction may start only after the search
                    // projection for this version has been committed.
                    cacheClient.markSourceSearchProjected(event, publishedAt);
                }
            }
            log.info("Projected {} source event(s) ({} upsert, {} delete, {}) into tenant '{}' index",
                    pending.size(), upserts.size(), deletes.size(),
                    fullRebuild ? "full rebuild" : "incremental", tenant);
        } catch (Exception failure) {
            log.warn("Search projection poll failed for tenant '{}'; events stay unpublished for retry",
                    tenant, failure);
        }
    }

    /**
     * Resolves the content refs of a source deletion. Only refs with no live
     * (non-tombstoned) attribution are returned, so a content-addressed chunk
     * still used by another source stays indexed. Returns {@code null} when the
     * tombstone/version manifest cannot be resolved (full-rebuild fallback).
     */
    private Set<UUID> resolveDeletedContentRefs(String tenant, SourceOutboxRow event) {
        if (event.tombstoneId() == null) {
            return null;
        }
        SourceTombstoneRow tombstone = cacheClient.readLatestSourceTombstone(
                        tenant, event.connectorId(), event.sourceId(), event.resourceId())
                .orElse(null);
        if (tombstone == null || !event.tombstoneId().equals(tombstone.tombstoneId())) {
            return null;
        }
        if (tombstone.versionsManifestRef() == null) {
            return Set.of();
        }
        Set<UUID> tombstoned = cacheClient.readTombstonedVersionIds(tenant);
        Set<UUID> refs = new LinkedHashSet<>();
        for (UUID versionId : cacheClient.readVersionManifest(tenant, tombstone.versionsManifestRef())) {
            cacheClient.readSourceVersion(
                            tenant, event.connectorId(), event.sourceId(), event.resourceId(), versionId)
                    .map(SourceVersionRow::contentRefId)
                    .ifPresent(refs::add);
        }
        Set<UUID> deletable = new LinkedHashSet<>();
        for (UUID ref : refs) {
            boolean live = cacheClient.readChunks(tenant, ref).stream()
                    .flatMap(chunk -> chunk.sourceVersionIds() == null
                            ? Stream.empty() : chunk.sourceVersionIds().stream())
                    .anyMatch(versionId -> !tombstoned.contains(versionId));
            if (!live) {
                deletable.add(ref);
            }
        }
        return deletable;
    }

    private UUID resolveContentRef(String tenant, SourceOutboxRow event) {
        if (event.sourceVersionId() == null) {
            return null;
        }
        return cacheClient.readSourceVersion(
                        tenant, event.connectorId(), event.sourceId(), event.resourceId(), event.sourceVersionId())
                .map(SourceVersionRow::contentRefId)
                .orElse(null);
    }

}
