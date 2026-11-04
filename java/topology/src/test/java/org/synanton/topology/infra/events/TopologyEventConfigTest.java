package org.synanton.topology.infra.events;

import org.junit.jupiter.api.Test;
import org.synanton.eventing.outbox.KafkaOutboxDispatcher;
import org.synanton.eventing.outbox.NoOpDispatcher;
import org.synanton.eventing.outbox.OutboxDispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopologyEventConfigTest {

    private final TopologyEventConfig config = new TopologyEventConfig();

    @Test
    void demoMode_returnsNoOpDispatcher() {
        OutboxDispatcher dispatcher = config.topologyOutboxDispatcher("demo", "", "topology.events");

        assertThat(dispatcher).isInstanceOf(NoOpDispatcher.class);
    }

    @Test
    void blankBootstrapServersWithoutDemoMode_failsAtStartup() {
        assertThatThrownBy(() -> config.topologyOutboxDispatcher("", "", "topology.events"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap-servers");
    }

    @Test
    void bootstrapServersSet_returnsKafkaDispatcher() {
        OutboxDispatcher dispatcher = config.topologyOutboxDispatcher("", "localhost:9092", "topology.events");

        assertThat(dispatcher).isInstanceOf(KafkaOutboxDispatcher.class);
        ((KafkaOutboxDispatcher) dispatcher).close();
    }
}
