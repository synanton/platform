package org.synanton.relix.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.synanton.common.resolution.GraphCapabilities;
import org.synanton.relix.graph.GraphConnector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromotionCapabilityWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PromotionCapabilityConfig.class);

    @Test
    void enabledWithoutPromotionCapabilitiesFailsAtStartup() {
        GraphConnector graph = mock(GraphConnector.class);
        when(graph.capabilities()).thenReturn(GraphCapabilities.NONE);

        runner.withBean(GraphConnector.class, () -> graph)
                .withPropertyValues("relix.promotion-consumer.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("requires conditional, atomic, idempotent relation promotion");
                });
    }

    @Test
    void enabledWithNeo4jCapabilitiesStarts() {
        GraphConnector graph = mock(GraphConnector.class);
        when(graph.capabilities()).thenReturn(GraphCapabilities.neo4j());

        runner.withBean(GraphConnector.class, () -> graph)
                .withPropertyValues("relix.promotion-consumer.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void disabledDoesNotValidate() {
        runner.withPropertyValues("relix.promotion-consumer.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
