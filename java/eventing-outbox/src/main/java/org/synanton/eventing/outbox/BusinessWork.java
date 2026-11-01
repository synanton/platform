package org.synanton.eventing.outbox;

import java.sql.Connection;

/**
 * Domain-state write that must share the caller's outbox transaction. If this
 * work throws, the transaction rolls back and the outbox append is discarded
 * with it, so no event is dispatched for a state change that never committed.
 */
@FunctionalInterface
public interface BusinessWork {

    void execute(Connection connection);

    BusinessWork NONE = connection -> { };
}
