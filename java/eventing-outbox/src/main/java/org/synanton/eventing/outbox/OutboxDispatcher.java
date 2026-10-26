package org.synanton.eventing.outbox;

/** Broker-agnostic dispatch seam. Implementations must preserve the outbox event ID. */
@FunctionalInterface
public interface OutboxDispatcher {

    void dispatch(OutboxEntry entry);
}
