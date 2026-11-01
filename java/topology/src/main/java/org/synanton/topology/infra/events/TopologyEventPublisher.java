package org.synanton.topology.infra.events;

import org.springframework.stereotype.Component;
import org.synanton.eventing.contract.ActorKind;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.Producer;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.contract.SecurityMetadata;
import org.synanton.eventing.outbox.BusinessWork;
import org.synanton.eventing.outbox.OutboxEventPublisher;
import org.synanton.topology.domain.event.ClassGrantRevoke;
import org.synanton.topology.domain.event.ClassGrantUpsert;
import org.synanton.topology.domain.event.GrantCreated;
import org.synanton.topology.domain.event.TenantCreated;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Conformant publication facade for topology. Emits three fact types through the shared
 * outbox publisher and returns the generated {@code event_id}, which the caller uses as
 * {@code PropagationId.outboxId}. All three types are {@link ReplayClass#REPLAY_SAFE}
 * (the grant model has no monotonic version, so consumers dedupe by event_id and tolerate
 * last-write-wins on out-of-order delivery — see #394).
 */
@Component
public class TopologyEventPublisher {

    public static final String TENANT_CREATED = "TenantCreated";
    public static final String GRANT_CREATED = "GrantCreated";
    public static final String CLASS_GRANT_UPSERT = "ClassGrantUpsert";
    public static final String CLASS_GRANT_REVOKE = "ClassGrantRevoke";

    private final OutboxEventPublisher publisher;
    private final Clock clock;

    public TopologyEventPublisher(OutboxEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public UUID publishTenantCreated(String tenantId, TenantCreated payload, BusinessWork businessWork) {
        return publish(tenantId, TENANT_CREATED, payload, businessWork);
    }

    public UUID publishGrantCreated(String tenantId, GrantCreated payload, BusinessWork businessWork) {
        return publish(tenantId, GRANT_CREATED, payload, businessWork);
    }

    public UUID publishClassGrantUpsert(String tenantId, ClassGrantUpsert payload, BusinessWork businessWork) {
        return publish(tenantId, CLASS_GRANT_UPSERT, payload, businessWork);
    }

    public UUID publishClassGrantRevoke(String tenantId, ClassGrantRevoke payload, BusinessWork businessWork) {
        return publish(tenantId, CLASS_GRANT_REVOKE, payload, businessWork);
    }

    private <T> UUID publish(String tenantId, String eventType, T payload, BusinessWork businessWork) {
        UUID eventId = UUID.randomUUID();
        Event<T> event = new Event<>(
                eventId,
                eventType,
                1,
                Instant.now(clock),
                new Producer("topology", "0.1.0"),
                tenantId,
                Optional.empty(),
                UUID.randomUUID(),
                Optional.empty(),
                ReplayClass.REPLAY_SAFE,
                Optional.empty(),
                new SecurityMetadata(ActorKind.SERVICE, "topology", "UNCLASSIFIED", 1, List.of()),
                payload);
        publisher.publish(tenantId, event, businessWork);
        return eventId;
    }
}
