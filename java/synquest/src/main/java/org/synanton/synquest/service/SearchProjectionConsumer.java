package org.synanton.synquest.service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
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
            @Value("${synquest.seed.demo-tenant:}") String tenant,
            @Value("${synquest.projection.batch-limit:200}") int batchLimit,
            @Value("${synquest.projection.enabled:true}") boolean projectionEnabled) {
        this.cacheClient = cacheClient;
        this.searchService = searchService;
        this.tenant = tenant;
        this.batchLimit = batchLimit;
        this.projectionEnabled = projectionEnabled;
    }

    /**
     * Fail-fast (Fix 1 pattern): an enabled projection with no tenant would silently
     * no-op — ingestion keeps writing to Cassandra while the search index never
     * updates and the outbox grows unbounded. Refuse to start instead.
     */
    @PostConstruct
    public void validateProjectionConfig() {
        if (!projectionEnabled) {
            log.warn("Search projection consumer is DISABLED; the search index will not update automatically");
            return;
        }
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException(
                    "Projection is enabled but no tenant is configured. Set synquest.seed.demo-tenant=<tenant> "
                            + "or disable with synquest.projection.enabled=false.");
        }
        log.info("Search projection consumer active for tenant '{}'", tenant);
    }

    @Scheduled(fixedDelayString = "${synquest.projection.poll-delay-ms:5000}")
    public void projectNewSourceVersions() {
        if (!projectionEnabled || tenant == null || tenant.isBlank()) {
            return;
        }
        try {
            List<SourceOutboxRow> pending =
                    cacheClient.listUnpublishedSourceOutbox(tenant, batchLimit, Instant.now());
            if (pending.isEmpty()) {
                return;
            }
            boolean fullRebuild = false;
            Set<UUID> contentRefs = new LinkedHashSet<>();
            for (SourceOutboxRow event : pending) {
                if (!"SourcePublished".equals(event.eventType())) {
                    // Deletes (and any future event type) fall back to a full
                    // rebuild: correctness first, and deletes are rare (#547).
                    fullRebuild = true;
                    break;
                }
                UUID contentRef = resolveContentRef(event);
                if (contentRef == null) {
                    fullRebuild = true;
                    break;
                }
                contentRefs.add(contentRef);
            }

            if (fullRebuild) {
                searchService.reindex(tenant);
            } else {
                searchService.projectContentRefs(tenant, contentRefs);
            }

            Instant publishedAt = Instant.now();
            for (SourceOutboxRow event : pending) {
                cacheClient.markSourceOutboxPublished(event, publishedAt);
            }
            log.info("Projected {} source event(s) ({} content ref(s), {}) into tenant '{}' index",
                    pending.size(), contentRefs.size(),
                    fullRebuild ? "full rebuild" : "incremental", tenant);
        } catch (Exception failure) {
            log.warn("Search projection poll failed for tenant '{}'; events stay unpublished for retry",
                    tenant, failure);
        }
    }

    private UUID resolveContentRef(SourceOutboxRow event) {
        if (event.sourceVersionId() == null) {
            return null;
        }
        return cacheClient.readSourceVersion(
                        tenant, event.connectorId(), event.sourceId(), event.resourceId(), event.sourceVersionId())
                .map(SourceVersionRow::contentRefId)
                .orElse(null);
    }

}
