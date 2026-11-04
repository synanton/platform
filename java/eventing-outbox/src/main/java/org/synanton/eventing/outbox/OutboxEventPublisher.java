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
    private final BusinessWork businessWork;

    public OutboxEventPublisher(
            EventTypeRegistry eventTypes,
            EventOutbox outbox,
            OutboxDispatcher dispatcher,
            OutboxTransaction transaction) {
        this(eventTypes, outbox, dispatcher, transaction, BusinessWork.NONE);
    }

    /** The business work runs in the same transaction as the outbox append, then dispatch after commit. */
    public OutboxEventPublisher(
            EventTypeRegistry eventTypes,
            EventOutbox outbox,
            OutboxDispatcher dispatcher,
            OutboxTransaction transaction,
            BusinessWork businessWork) {
        super(eventTypes);
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.businessWork = Objects.requireNonNull(businessWork, "businessWork");
    }

    @Override
    protected void deliver(Event<?> event) {
        deliver(event, businessWork);
    }

    /**
     * Publishes with per-call business work that runs in the same transaction as the outbox
     * append for this single call. Use this when the domain write differs per event and is only
     * known at call time; the constructor's fixed {@link BusinessWork} is not applied.
     */
    public void publish(String verifiedTenantId, Event<?> event, BusinessWork perCallBusinessWork) {
        validate(verifiedTenantId, event);
        deliver(event, Objects.requireNonNull(perCallBusinessWork, "perCallBusinessWork"));
    }

    private void deliver(Event<?> event, BusinessWork perCallBusinessWork) {
        OutboxEntry entry = transaction.execute(connection -> {
            perCallBusinessWork.execute(connection);
            return outbox.append(connection, event);
        });
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
