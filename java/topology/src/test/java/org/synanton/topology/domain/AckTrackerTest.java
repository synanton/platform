package org.synanton.topology.domain;

import org.junit.jupiter.api.Test;
import org.synanton.topology.domain.model.PropagationId;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AckTrackerTest {

    @Test
    void shouldAwaitUntilAllConsumersAck() {
        AckTracker tracker = new AckTracker();
        UUID outboxId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        List<String> consumers = List.of("synquest", "gateway", "relix");
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            tracker.record(outboxId, "synquest", 0);
            tracker.record(outboxId, "gateway", 0);
            tracker.record(outboxId, "relix", 0);
        });
        boolean acked = tracker.await(outboxId, consumers, Duration.ofMillis(200));
        assertThat(acked).isTrue();
        assertThat(tracker.state(outboxId, consumers)).isEqualTo(PropagationId.PROPAGATED);
    }
}
