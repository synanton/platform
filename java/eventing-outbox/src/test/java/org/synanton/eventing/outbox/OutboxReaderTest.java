package org.synanton.eventing.outbox;

import org.junit.jupiter.api.Test;
import org.synanton.eventing.contract.ActorKind;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.Producer;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.contract.SecurityMetadata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxReaderTest {

    private static final UUID FIRST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CORRELATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Test
    void drain_publishesEveryPendingRowAndMarksDispatched() {
        InMemoryEventOutbox outbox = new InMemoryEventOutbox();
        outbox.append(null, event(FIRST_ID));
        outbox.append(null, event(SECOND_ID));
        RecordingDispatcher dispatcher = new RecordingDispatcher(0);
        OutboxReader reader = new OutboxReader(outbox, dispatcher, 10);

        int dispatched = reader.drain();

        assertThat(dispatched).isEqualTo(2);
        assertThat(dispatcher.eventIds).containsExactly(FIRST_ID, SECOND_ID);
        assertThat(outbox.isDispatched(FIRST_ID)).isTrue();
        assertThat(outbox.isDispatched(SECOND_ID)).isTrue();
        assertThat(outbox.pending(10)).isEmpty();
    }

    @Test
    void drainFailure_leavesTheRowPendingAndRetryReusesTheSameEventId() {
        InMemoryEventOutbox outbox = new InMemoryEventOutbox();
        outbox.append(null, event(FIRST_ID));
        RecordingDispatcher dispatcher = new RecordingDispatcher(1);
        OutboxReader reader = new OutboxReader(outbox, dispatcher, 10);

        int firstPass = reader.drain();

        assertThat(firstPass).isZero();
        assertThat(outbox.isDispatched(FIRST_ID)).isFalse();
        assertThat(outbox.pending(10)).extracting(OutboxEntry::eventId).containsExactly(FIRST_ID);
        assertThat(outbox.find(FIRST_ID)).hasValueSatisfying(entry -> assertThat(entry.attempts()).isEqualTo(1));

        int secondPass = reader.drain();

        assertThat(secondPass).isEqualTo(1);
        assertThat(dispatcher.eventIds).containsExactly(FIRST_ID, FIRST_ID);
        assertThat(outbox.isDispatched(FIRST_ID)).isTrue();
        assertThat(outbox.pending(10)).isEmpty();
    }

    @Test
    void drain_recordsDispatchedAndFailedMetrics() {
        InMemoryEventOutbox outbox = new InMemoryEventOutbox();
        outbox.append(null, event(FIRST_ID));
        RecordingDispatcher dispatcher = new RecordingDispatcher(1);
        OutboxMetrics metrics = new OutboxMetrics();
        OutboxReader reader = new OutboxReader(outbox, dispatcher, 10, metrics);

        reader.drain();

        assertThat(metrics.failedCount()).isEqualTo(1);
        assertThat(metrics.dispatchedCount()).isZero();

        reader.drain();

        assertThat(metrics.dispatchedCount()).isEqualTo(1);
        assertThat(metrics.failedCount()).isEqualTo(1);
    }

    @Test
    void drain_afterRestartPublishesRowsLeftByAStoppedProcess() {
        InMemoryEventOutbox outbox = new InMemoryEventOutbox();
        outbox.append(null, event(FIRST_ID));
        outbox.append(null, event(SECOND_ID));

        OutboxReader crashed = new OutboxReader(outbox, new FailingDispatcher(), 10);
        crashed.drain();
        crashed.close();

        assertThat(outbox.pending(10)).extracting(OutboxEntry::eventId)
                .containsExactly(FIRST_ID, SECOND_ID);

        RecordingDispatcher restarted = new RecordingDispatcher(0);
        OutboxReader recovery = new OutboxReader(outbox, restarted, 10);
        recovery.drain();
        recovery.close();

        assertThat(restarted.eventIds).containsExactly(FIRST_ID, SECOND_ID);
        assertThat(outbox.isDispatched(FIRST_ID)).isTrue();
        assertThat(outbox.isDispatched(SECOND_ID)).isTrue();
    }

    private static Event<String> event(UUID eventId) {
        return new Event<>(
                eventId,
                "ContentPublished",
                1,
                Instant.parse("2026-10-26T12:00:00Z"),
                new Producer("content-cache", "1.26"),
                "tenant-a",
                Optional.empty(),
                CORRELATION_ID,
                Optional.empty(),
                ReplayClass.REPLAY_SAFE,
                Optional.empty(),
                new SecurityMetadata(ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of()),
                "payload");
    }

    private static final class RecordingDispatcher implements OutboxDispatcher {

        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<UUID> eventIds = new ArrayList<>();

        private RecordingDispatcher(int failures) {
            this.failures = failures;
        }

        @Override
        public void dispatch(OutboxEntry entry) {
            eventIds.add(entry.eventId());
            if (calls.getAndIncrement() < failures) {
                throw new IllegalStateException("broker unavailable");
            }
        }
    }

    private static final class FailingDispatcher implements OutboxDispatcher {

        @Override
        public void dispatch(OutboxEntry entry) {
            throw new IllegalStateException("broker unavailable");
        }
    }
}
