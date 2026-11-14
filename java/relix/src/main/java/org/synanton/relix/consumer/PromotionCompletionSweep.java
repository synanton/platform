package org.synanton.relix.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.relix.config.PromotionCompletionProperties;

/**
 * Bounded zero-row/terminal completion path for revisions whose mention graph
 * committed before promotion work existed or whose promotion worker stopped
 * before writing the canonical boundary. It scans only tenant/source/identity
 * partitions with graph_status=MENTIONS_COMMITTED.
 */
@Component
@ConditionalOnProperty(name = "relix.promotion-completion.enabled", havingValue = "true")
public final class PromotionCompletionSweep {

    private static final Logger log = LoggerFactory.getLogger(PromotionCompletionSweep.class);

    private final IngestionCacheClient cache;
    private final CanonicalGraphCompletion completion;
    private final PromotionCompletionProperties properties;
    private final MeterRegistry registry;

    public PromotionCompletionSweep(IngestionCacheClient cache,
                                    CanonicalGraphCompletion completion,
                                    PromotionCompletionProperties properties,
                                    MeterRegistry registry) {
        this.cache = cache;
        this.completion = completion;
        this.properties = properties;
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${relix.promotion-completion.poll-delay-ms:30000}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        String tenant = properties.tenant();
        if (tenant == null || tenant.isBlank()) {
            log.warn("Promotion completion sweep is enabled without a tenant; skipping poll");
            return;
        }
        for (SourceVersionRow version : cache.listMentionsCommittedSourceVersions(
                tenant, properties.batch())) {
            try {
                if (completion.completeIfPromotionTerminal(version)) {
                    registry.counter("promotion.completion", "outcome", "COMPLETED").increment();
                }
            } catch (Exception e) {
                registry.counter("promotion.completion", "outcome", "FAILED").increment();
                log.warn("Canonical graph completion failed tenant={} source={} generation={}: {}",
                        version.tenantId(), version.sourceId(), version.generation(), e.toString());
            }
        }
    }
}
