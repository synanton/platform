package org.synanton.eventing.publisher;

import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventEnvelopeValidator;
import org.synanton.eventing.contract.EventTypeRegistry;

import java.util.Objects;

public abstract class AbstractEventPublisher implements EventPublisher {

    private final EventTypeRegistry eventTypes;

    protected AbstractEventPublisher(EventTypeRegistry eventTypes) {
        this.eventTypes = Objects.requireNonNull(eventTypes, "eventTypes");
    }

    @Override
    public final void publish(String verifiedTenantId, Event<?> event) {
        validate(verifiedTenantId, event);
        deliver(event);
    }

    /** Tenant-scope and registry admission; shared by per-publish overrides in subclasses. */
    protected final void validate(String verifiedTenantId, Event<?> event) {
        EventEnvelopeValidator.validate(verifiedTenantId, event, eventTypes);
    }

    protected abstract void deliver(Event<?> event);
}
