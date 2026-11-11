package org.synanton.relix.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.relix.config.ReconciliationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReconciliationWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ReconciliationConfig.class)
            .withBean(IngestionCacheClient.class, () -> mock(IngestionCacheClient.class))
            .withBean(ClaimService.class, () -> mock(ClaimService.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(ReconciliationProperties.class,
                    () -> new ReconciliationProperties(true, "tenant", 10, 1000L, 60));

    @Test
    void enabledWithoutReconciliationCapabilityFailsAtStartup() {
        runner.withPropertyValues("relix.reconciliation.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("requires the configured graph adapter")
                            .hasStackTraceContaining("support resolution reconciliation")
                            .hasStackTraceContaining("#587/#592");
                });
    }

    @Test
    void disabledWithoutGraphSeamStartsCleanlyAndCreatesNoReconciler() {
        runner.withPropertyValues("relix.reconciliation.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ResolutionReconciler.class);
                });
    }
}
