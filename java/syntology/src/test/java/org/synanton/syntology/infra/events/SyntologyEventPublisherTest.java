package org.synanton.syntology.infra.events;

import org.junit.jupiter.api.Test;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.publisher.EventPublisher;
import org.synanton.syntology.domain.event.EntityCreated;
import org.synanton.syntology.domain.event.OntologyCreated;
import org.synanton.syntology.domain.event.VersionBumped;
import org.synanton.syntology.domain.event.VersionBumpSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SyntologyEventPublisherTest {

    private static final UUID VERSION_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void publishVersionBumped_emitsTheConformantEnvelope() {
        RecordingPublisher publisher = new RecordingPublisher();
        SyntologyEventPublisher events = new SyntologyEventPublisher(publisher, CLOCK);

        events.publishVersionBumped("tenant-a", new VersionBumped(VERSION_ID, "1.1.0", VersionBumpSource.HCL));

        Event<?> published = publisher.single();
        assertThat(published.eventType()).isEqualTo(SyntologyEventPublisher.VERSION_BUMPED);
        assertThat(published.eventVersion()).isEqualTo(1);
        assertThat(published.tenantId()).isEqualTo("tenant-a");
        assertThat(published.replayClass()).isEqualTo(ReplayClass.REPLAY_SAFE);
        assertThat(published.producer().service()).isEqualTo("syntology");
        assertThat(published.payload()).isEqualTo(new VersionBumped(VERSION_ID, "1.1.0", VersionBumpSource.HCL));
    }

    @Test
    void publishOntologyCreated_emitsItsTypeAndPayload() {
        RecordingPublisher publisher = new RecordingPublisher();
        SyntologyEventPublisher events = new SyntologyEventPublisher(publisher, CLOCK);

        events.publishOntologyCreated("tenant-a", new OntologyCreated(VERSION_ID, "1.0.0"));

        Event<?> published = publisher.single();
        assertThat(published.eventType()).isEqualTo(SyntologyEventPublisher.ONTOLOGY_CREATED);
        assertThat(published.payload()).isEqualTo(new OntologyCreated(VERSION_ID, "1.0.0"));
    }

    @Test
    void publishEntityCreated_emitsItsTypeAndPayload() {
        RecordingPublisher publisher = new RecordingPublisher();
        SyntologyEventPublisher events = new SyntologyEventPublisher(publisher, CLOCK);

        events.publishEntityCreated("tenant-a", new EntityCreated("Product"));

        Event<?> published = publisher.single();
        assertThat(published.eventType()).isEqualTo(SyntologyEventPublisher.ENTITY_CREATED);
        assertThat(published.payload()).isEqualTo(new EntityCreated("Product"));
    }

    private static final class RecordingPublisher implements EventPublisher {

        private final List<Event<?>> delivered = new ArrayList<>();

        @Override
        public void publish(String verifiedTenantId, Event<?> event) {
            delivered.add(event);
        }

        private Event<?> single() {
            assertThat(delivered).hasSize(1);
            return delivered.get(0);
        }
    }
}
