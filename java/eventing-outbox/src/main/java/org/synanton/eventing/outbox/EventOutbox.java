package org.synanton.eventing.outbox;

import org.synanton.eventing.contract.Event;

import java.util.List;
import java.util.UUID;

/** Transactional outbox for events that must survive a broker outage. */
public interface EventOutbox {

    /** Appends the event and returns the durable row. Re-appending the same event ID is idempotent. */
    OutboxEntry append(Event<?> event);

    /** Returns undispatched rows ordered by creation time. */
    List<OutboxEntry> pending(int limit);

    /** Marks a row dispatched; returns false when the row was already dispatched or is absent. */
    boolean markDispatched(UUID eventId);

    /** Records a failed attempt without discarding the row. */
    boolean markFailed(UUID eventId, String error);
}
