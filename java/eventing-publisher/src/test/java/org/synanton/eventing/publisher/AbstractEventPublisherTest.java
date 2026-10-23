package org.synanton.eventing.publisher;

import org.junit.jupiter.api.Test;
import org.synanton.eventing.contract.ActorKind;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventTypeDeclaration;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.Producer;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.contract.SecurityMetadata;
import org.synanton.eventing.contract.TenantScopeMismatchException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractEventPublisherTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CORRELATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CAUSATION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private final EventTypeRegistry eventTypes = new EventTypeRegistry(List.of(
            new EventTypeDeclaration("ContentPublished", 1, ReplayClass.REPLAY_SAFE, Optional.empty())));

    @Test
    void publish_retryReusesEventIdentityCorrelationAndCausation() {
        RecordingPublisher publisher = new RecordingPublisher(eventTypes);
        Event<String> event = event(1);

        publisher.publish("tenant-a", event);
        publisher.publish("tenant-a", event);

        assertThat(publisher.delivered).containsExactly(event, event);
        assertThat(publisher.delivered.get(1).eventId()).isEqualTo(EVENT_ID);
        assertThat(publisher.delivered.get(1).correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(publisher.delivered.get(1).causationId()).contains(CAUSATION_ID);
    }

    @Test
    void publish_crossTenantEvent_rejectedBeforeDelivery() {
        RecordingPublisher publisher = new RecordingPublisher(eventTypes);

        assertThatThrownBy(() -> publisher.publish("tenant-b", event(1)))
                .isInstanceOf(TenantScopeMismatchException.class);
        assertThat(publisher.delivered).isEmpty();
    }

    @Test
    void publish_unregisteredSchemaVersion_rejectedBeforeDelivery() {
        RecordingPublisher publisher = new RecordingPublisher(eventTypes);

        assertThatThrownBy(() -> publisher.publish("tenant-a", event(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
        assertThat(publisher.delivered).isEmpty();
    }

    @Test
    void publish_registeredEvent_deliversTheEnvelopeUnchanged() {
        RecordingPublisher publisher = new RecordingPublisher(eventTypes);
        Event<String> event = event(1);

        publisher.publish("tenant-a", event);

        assertThat(publisher.delivered).containsExactly(event);
    }

    private static Event<String> event(int eventVersion) {
        return new Event<>(
                EVENT_ID,
                "ContentPublished",
                eventVersion,
                Instant.parse("2026-10-23T12:00:00Z"),
                new Producer("content-cache", "1.26"),
                "tenant-a",
                Optional.empty(),
                CORRELATION_ID,
                Optional.of(CAUSATION_ID),
                ReplayClass.REPLAY_SAFE,
                Optional.empty(),
                new SecurityMetadata(
                        ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of()),
                "payload");
    }

    private static final class RecordingPublisher extends AbstractEventPublisher {

        private final List<Event<?>> delivered = new ArrayList<>();

        private RecordingPublisher(EventTypeRegistry eventTypes) {
            super(eventTypes);
        }

        @Override
        protected void deliver(Event<?> event) {
            delivered.add(event);
        }
    }
}
