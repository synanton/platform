package org.synanton.eventing.outbox;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Explicit JDBC transaction boundary; the publisher dispatches only after a successful commit. */
public final class JdbcOutboxTransaction implements OutboxTransaction {

    private final DataSource dataSource;

    public JdbcOutboxTransaction(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public OutboxEntry execute(TransactionWork work) {
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                OutboxEntry entry = work.execute(connection);
                connection.commit();
                return entry;
            } catch (RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw new IllegalStateException("outbox transaction failed", exception);
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // The connection is about to close; the commit/rollback outcome is authoritative.
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("outbox transaction failed", exception);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
