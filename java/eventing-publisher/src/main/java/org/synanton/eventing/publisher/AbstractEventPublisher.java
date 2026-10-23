package org.synanton.eventing.publisher;

import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.TenantScope;

import java.util.Objects;

public abstract class AbstractEventPublisher implements EventPublisher {

    private final EventTypeRegistry eventTypes;

    protected AbstractEventPublisher(EventTypeRegistry eventTypes) {
        this.eventTypes = Objects.requireNonNull(eventTypes, "eventTypes");
    }

    @Override
    public final void publish(String verifiedTenantId, Event<?> event) {
        Objects.requireNonNull(event, "event");
        TenantScope.requireMatches(verifiedTenantId, event);
        eventTypes.validate(event);
        deliver(event);
    }

    protected abstract void deliver(Event<?> event);
}
