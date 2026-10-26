package org.synanton.eventing.outbox;

import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.publisher.AbstractEventPublisher;

import java.util.Objects;

/** Commits the outbox row first, then dispatches. A dispatch failure leaves the row for retry. */
public final class OutboxEventPublisher extends AbstractEventPublisher {

    private final EventOutbox outbox;
    private final OutboxDispatcher dispatcher;
    private final OutboxTransaction transaction;

    public OutboxEventPublisher(
            EventTypeRegistry eventTypes,
            EventOutbox outbox,
            OutboxDispatcher dispatcher,
            OutboxTransaction transaction) {
        super(eventTypes);
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
    }

    @Override
    protected void deliver(Event<?> event) {
        OutboxEntry entry = transaction.execute(connection -> outbox.append(connection, event));
        dispatchAfterCommit(entry);
    }

    /** Call only after the business transaction that wrote {@code entry} has committed. */
    public void dispatchAfterCommit(OutboxEntry entry) {
        Objects.requireNonNull(entry, "entry");
        try {
            dispatcher.dispatch(entry);
            outbox.markDispatched(entry.eventId());
        } catch (RuntimeException exception) {
            outbox.markFailed(entry.eventId(), exception.getMessage());
            throw exception;
        }
    }
}
