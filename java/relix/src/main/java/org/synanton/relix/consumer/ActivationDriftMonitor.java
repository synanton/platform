package org.synanton.relix.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.List;
import org.springframework.stereotype.Component;
import org.synanton.ingestioncache.activation.SourceActivationStore;
import org.synanton.ingestioncache.activation.SourceLifecycleRecord;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.client.TenantPolling;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.relix.config.PromotionConsumerProperties;

/**
 * Read-time lifecycle drift observability: accepted generation minus current
 * generation for each configured source. This exposes the temporary-visibility
 * window until #655/#656 filter readers. It does not authorize or mutate.
 */
@Component
public final class ActivationDriftMonitor {

    private final IngestionCacheClient cache;
    private final SourceActivationStore activations;
    private final PromotionConsumerProperties properties;
    private final MeterRegistry registry;

    public ActivationDriftMonitor(IngestionCacheClient cache, SourceActivationStore activations,
                                  PromotionConsumerProperties properties, MeterRegistry registry) {
        this.cache = cache;
        this.activations = activations;
        this.properties = properties;
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${relix.activation-drift.poll-delay-ms:30000}")
    public void poll() {
        if (!properties.enabled()) {
            return;
        }
        List<String> tenants;
        try {
            tenants = TenantPolling.resolve(properties.tenant(), cache);
        } catch (RuntimeException failure) {
            return;
        }
        for (String tenant : tenants) {
            try {
                for (SourceRow source : cache.listSources(tenant)) {
                    activations.readLifecycle(tenant, source.sourceId()).ifPresent(this::record);
                }
            } catch (RuntimeException ignored) {
                // Drift is observational; one tenant must not block the others.
            }
        }
    }

    private void record(SourceLifecycleRecord lifecycle) {
        registry.gauge("activation.generation_drift",
                Tags.of("source", lifecycle.sourceId()), lifecycle,
                value -> (double) (value.acceptedGeneration() - value.currentGeneration()));
    }
}
