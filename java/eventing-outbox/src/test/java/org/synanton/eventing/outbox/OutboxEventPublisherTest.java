package org.synanton.eventing.outbox;

import org.junit.jupiter.api.Test;
import org.synanton.eventing.contract.ActorKind;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventTypeDeclaration;
import org.synanton.eventing.contract.EventTypeRegistry;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxEventPublisherTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CORRELATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final EventTypeRegistry eventTypes = new EventTypeRegistry(List.of(
            new EventTypeDeclaration("ContentPublished", 1, ReplayClass.REPLAY_SAFE, Optional.empty())));

    @Test
    void publish_persistsBeforeDispatchAndMarksTheRow() {
        InMemoryEventOutbox outbox = new InMemoryEventOutbox();
        RecordingDispatcher dispatcher = new RecordingDispatcher(0);
        OutboxEventPublisher publisher = new OutboxEventPublisher(eventTypes, outbox, dispatcher);

        publisher.publish("tenant-a", event());

        assertThat(dispatcher.eventIds).containsExactly(EVENT_ID);
        assertThat(outbox.pending(10)).isEmpty();
    }

    @Test
    void dispatchFailure_retainsRowAndRetryReEmitsTheSameEventId() {
        InMemoryEventOutbox outbox = new InMemoryEventOutbox();
        RecordingDispatcher dispatcher = new RecordingDispatcher(1);
        OutboxEventPublisher publisher = new OutboxEventPublisher(eventTypes, outbox, dispatcher);

        assertThatThrownBy(() -> publisher.publish("tenant-a", event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker unavailable");
        assertThat(outbox.pending(10))
                .extracting(OutboxEntry::eventId)
                .containsExactly(EVENT_ID);

        publisher.publish("tenant-a", event());

        assertThat(dispatcher.eventIds).containsExactly(EVENT_ID, EVENT_ID);
        assertThat(outbox.pending(10)).isEmpty();
    }

    @Test
    void outboxWriteFailure_doesNotDispatch() {
        RecordingDispatcher dispatcher = new RecordingDispatcher(0);
        OutboxEventPublisher publisher = new OutboxEventPublisher(eventTypes, new FailingOutbox(), dispatcher);

        assertThatThrownBy(() -> publisher.publish("tenant-a", event()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");
        assertThat(dispatcher.eventIds).isEmpty();
    }

    private static Event<String> event() {
        return new Event<>(
                EVENT_ID,
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
                new SecurityMetadata(
                        ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of()),
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

    private static final class FailingOutbox implements EventOutbox {

        @Override
        public OutboxEntry append(Event<?> event) {
            throw new IllegalStateException("outbox unavailable");
        }

        @Override
        public List<OutboxEntry> pending(int limit) {
            return List.of();
        }

        @Override
        public boolean markDispatched(UUID eventId) {
            return false;
        }

        @Override
        public boolean markFailed(UUID eventId, String error) {
            return false;
        }
    }
}
