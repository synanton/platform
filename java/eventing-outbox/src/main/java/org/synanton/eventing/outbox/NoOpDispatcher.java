package org.synanton.eventing.outbox;

/** Test and local default that records nothing and never fails. */
public final class NoOpDispatcher implements OutboxDispatcher {

    @Override
    public void dispatch(OutboxEntry entry) {
    }
}
