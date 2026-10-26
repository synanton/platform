package org.synanton.eventing.outbox;

import org.synanton.eventing.contract.Event;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Transactional outbox for events that must survive a broker outage. */
public interface EventOutbox {

    /**
     * Appends the event on the caller's transaction. The caller owns commit/rollback and connection close.
     * Re-appending the same event ID is idempotent.
     */
    OutboxEntry append(Connection connection, Event<?> event);

    /** Returns undispatched rows ordered by creation time. */
    List<OutboxEntry> pending(int limit);

    /** Returns the durable row even after dispatch. */
    Optional<OutboxEntry> find(UUID eventId);

    /** True only after a successful dispatch was recorded. */
    boolean isDispatched(UUID eventId);

    /** Marks a row dispatched; returns false when the row was already dispatched or is absent. */
    boolean markDispatched(UUID eventId);

    /** Records a failed attempt without discarding the row. */
    boolean markFailed(UUID eventId, String error);
}
