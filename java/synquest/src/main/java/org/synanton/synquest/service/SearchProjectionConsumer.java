package org.synanton.synquest.service;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.SourceOutboxRow;

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

    public SearchProjectionConsumer(
            IngestionCacheClient cacheClient,
            SearchService searchService,
            @Value("${synquest.seed.demo-tenant:}") String tenant,
            @Value("${synquest.projection.batch-limit:200}") int batchLimit) {
        this.cacheClient = cacheClient;
        this.searchService = searchService;
        this.tenant = tenant;
        this.batchLimit = batchLimit;
    }

    @Scheduled(fixedDelayString = "${synquest.projection.poll-delay-ms:5000}")
    public void projectNewSourceVersions() {
        if (tenant == null || tenant.isBlank()) {
            return;
        }
        try {
            List<SourceOutboxRow> pending =
                    cacheClient.listUnpublishedSourceOutbox(tenant, batchLimit, Instant.now());
            if (pending.isEmpty()) {
                return;
            }
            searchService.reindex(tenant);
            Instant publishedAt = Instant.now();
            for (SourceOutboxRow event : pending) {
                cacheClient.markSourceOutboxPublished(event, publishedAt);
            }
            log.info("Projected {} source event(s) into tenant '{}' index", pending.size(), tenant);
        } catch (Exception failure) {
            log.warn("Search projection poll failed for tenant '{}'; events stay unpublished for retry",
                    tenant, failure);
        }
    }
}
