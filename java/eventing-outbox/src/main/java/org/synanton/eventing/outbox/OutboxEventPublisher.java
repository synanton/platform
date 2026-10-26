package org.synanton.eventing.outbox;

import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.publisher.AbstractEventPublisher;

import java.util.Objects;

/** Persists first, then attempts dispatch. A dispatch failure leaves the row for retry. */
public final class OutboxEventPublisher extends AbstractEventPublisher {

    private final EventOutbox outbox;
    private final OutboxDispatcher dispatcher;

    public OutboxEventPublisher(
            EventTypeRegistry eventTypes,
            EventOutbox outbox,
            OutboxDispatcher dispatcher) {
        super(eventTypes);
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    protected void deliver(Event<?> event) {
        OutboxEntry entry = outbox.append(event);
        try {
            dispatcher.dispatch(entry);
            outbox.markDispatched(entry.eventId());
        } catch (RuntimeException exception) {
            outbox.markFailed(entry.eventId(), exception.getMessage());
            throw exception;
        }
    }
}
