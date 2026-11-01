package org.synanton.topology.infra.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Explicit JDBC transaction boundary for topology mutations. The grant, outbox, and audit
 * writes share one connection and commit together; any failure rolls the whole unit back.
 */
public final class TransactionalJdbc {

    private TransactionalJdbc() {
    }

    public static <T> T inTransaction(DataSource dataSource, ConnectionWork<T> work) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw new IllegalStateException("transaction failed", exception);
            } finally {
                resetAutoCommit(connection, previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("transaction failed", exception);
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void resetAutoCommit(Connection connection, boolean previous) {
        try {
            connection.setAutoCommit(previous);
        } catch (SQLException ignored) {
            // Connection is about to close; the commit/rollback outcome is authoritative.
        }
    }

    /** Work that runs on the caller's transaction connection. */
    @FunctionalInterface
    public interface ConnectionWork<T> {

        T execute(Connection connection);
    }
}
