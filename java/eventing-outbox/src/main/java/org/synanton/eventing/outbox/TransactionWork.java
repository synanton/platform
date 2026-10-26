package org.synanton.eventing.outbox;

import java.sql.Connection;

/** Work that must share the caller's outbox transaction. */
@FunctionalInterface
public interface TransactionWork {

    OutboxEntry execute(Connection connection);
}
