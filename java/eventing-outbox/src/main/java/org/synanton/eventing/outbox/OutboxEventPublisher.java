package org.synanton.eventing.outbox;

import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.publisher.AbstractEventPublisher;

import java.util.Objects;
import java.util.UUID;

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
        dispatchAfterCommit(entry.eventId());
    }

    /** Call only after the transaction that wrote the row has committed; reads the durable row. */
    public void dispatchAfterCommit(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        OutboxEntry entry = outbox.find(eventId)
                .orElseThrow(() -> new IllegalStateException("outbox row missing after commit"));
        try {
            dispatcher.dispatch(entry);
            outbox.markDispatched(entry.eventId());
        } catch (RuntimeException exception) {
            outbox.markFailed(entry.eventId(), exception.getMessage());
            throw exception;
        }
    }
}
