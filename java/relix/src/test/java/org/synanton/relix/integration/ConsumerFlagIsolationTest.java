package org.synanton.relix.integration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.synanton.common.resolution.ResolutionCandidateProvider;
import org.synanton.common.resolution.ResolutionGraphPort;
import org.synanton.common.resolution.ResolutionPolicy;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.relix.config.PromotionConsumerProperties;
import org.synanton.relix.config.ReconciliationProperties;
import org.synanton.relix.consumer.PromotionConsumer;
import org.synanton.relix.graph.CanonicalRelationPromotionPort;
import org.synanton.relix.reconciliation.ResolutionReconciler;
import org.synanton.synquest.config.ResolutionConsumerProperties;
import org.synanton.synquest.resolution.ResolutionConsumer;
import org.synanton.synquest.resolution.ResolutionWriteClient;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ConsumerFlagIsolationTest {

    private final ClaimService claims = mock(ClaimService.class);
    private final IngestionCacheClient cache = mock(IngestionCacheClient.class);
    private final ResolutionGraphPort graph = mock(ResolutionGraphPort.class);

    @Test
    void disabledResolutionConsumerDoesNothing() {
        ResolutionConsumer consumer = new ResolutionConsumer(claims,
                mock(ResolutionCandidateProvider.class), mock(ResolutionPolicy.class),
                mock(ResolutionWriteClient.class),
                new ResolutionConsumerProperties(false, "tenant", 10, 5, 1, 60, 1000L, "active"),
                new SimpleMeterRegistry());

        consumer.poll();

        verifyNoInteractions(claims);
    }

    @Test
    void disabledPromotionConsumerDoesNothing() {
        CanonicalRelationPromotionPort port = mock(CanonicalRelationPromotionPort.class);
        PromotionConsumer consumer = new PromotionConsumer(claims, port,
                new PromotionConsumerProperties(false, "tenant", 5, 5, 1, 60, 1000L),
                new SimpleMeterRegistry());

        consumer.poll();

        verifyNoInteractions(claims, port);
    }

    @Test
    void disabledReconcilerDoesNothing() {
        ResolutionReconciler reconciler = new ResolutionReconciler(cache, claims, graph,
                new ReconciliationProperties(false, "tenant", 10, 1000L, 60),
                new SimpleMeterRegistry());

        reconciler.poll();

        verifyNoInteractions(cache, claims, graph);
    }
}
