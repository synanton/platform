package org.synanton.relix.consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.activation.ActivationState;
import org.synanton.ingestioncache.activation.SourceActivationStore;
import org.synanton.ingestioncache.activation.SourceLifecycleRecord;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.relix.config.PromotionConsumerProperties;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActivationDriftMonitorTest {

    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final SourceActivationStore activations = mock(SourceActivationStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PromotionConsumerProperties properties =
            new PromotionConsumerProperties(true, "tenant-a", 5, 5, 30, 120, 10000L);

    @Test
    void recordsAcceptedMinusCurrentGenerationPerSource() {
        SourceRow source = new SourceRow("tenant-a", "source-a", "connector", "upload",
                null, Set.of(), "ACTIVE", Instant.now(), null);
        when(cache.listSources("tenant-a")).thenReturn(List.of(source));
        when(activations.readLifecycle("tenant-a", "source-a")).thenReturn(Optional.of(
                new SourceLifecycleRecord("tenant-a", "source-a", 3, 1,
                        UUID.randomUUID(), UUID.randomUUID(), ActivationState.SEARCH_COMMITTED,
                        null, true, Instant.now())));

        new ActivationDriftMonitor(cache, activations, properties, registry).poll();

        assertThat(registry.get("activation.generation_drift").tag("source", "source-a")
                .gauge().value()).isEqualTo(2);
    }
}
