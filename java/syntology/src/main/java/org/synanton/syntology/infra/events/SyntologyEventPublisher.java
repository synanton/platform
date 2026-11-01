package org.synanton.syntology.infra.events;

import org.springframework.stereotype.Component;
import org.synanton.eventing.contract.ActorKind;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.Producer;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.contract.SecurityMetadata;
import org.synanton.eventing.publisher.EventPublisher;
import org.synanton.syntology.domain.event.EntityCreated;
import org.synanton.syntology.domain.event.OntologyCreated;
import org.synanton.syntology.domain.event.VersionBumped;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Conformant publication facade: emits syntology's three facts as EWP-1 {@link Event}
 * envelopes through the shared outbox publisher. All three types are {@link ReplayClass#REPLAY_SAFE};
 * consumers must dedupe by {@code event_id} (EWP-4) and apply a monotonic version comparison for
 * {@code VersionBumped}.
 */
@Component
public class SyntologyEventPublisher {

    public static final String ONTOLOGY_CREATED = "OntologyCreated";
    public static final String VERSION_BUMPED = "VersionBumped";
    public static final String ENTITY_CREATED = "EntityCreated";

    private final EventPublisher publisher;
    private final Clock clock;

    public SyntologyEventPublisher(EventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public void publishOntologyCreated(String tenantId, OntologyCreated payload) {
        publish(tenantId, ONTOLOGY_CREATED, payload);
    }

    public void publishVersionBumped(String tenantId, VersionBumped payload) {
        publish(tenantId, VERSION_BUMPED, payload);
    }

    public void publishEntityCreated(String tenantId, EntityCreated payload) {
        publish(tenantId, ENTITY_CREATED, payload);
    }

    private <T> void publish(String tenantId, String eventType, T payload) {
        Event<T> event = new Event<>(
                UUID.randomUUID(),
                eventType,
                1,
                Instant.now(clock),
                new Producer("syntology", "0.1.0"),
                tenantId,
                Optional.empty(),
                UUID.randomUUID(),
                Optional.empty(),
                ReplayClass.REPLAY_SAFE,
                Optional.empty(),
                new SecurityMetadata(ActorKind.SERVICE, "syntology", "UNCLASSIFIED", 1, List.of()),
                payload);
        publisher.publish(tenantId, event);
    }
}
