package org.synanton.eventing.outbox;

/** Runs outbox work and commits before returning, so dispatch can happen strictly after commit. */
@FunctionalInterface
public interface OutboxTransaction {

    OutboxEntry execute(TransactionWork work);
}
