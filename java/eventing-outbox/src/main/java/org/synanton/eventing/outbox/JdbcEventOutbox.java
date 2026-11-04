package org.synanton.eventing.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventingJson;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * PostgreSQL outbox. Append uses the caller's connection and never commits or closes it.
 * The table is unqualified by default ({@code event_outbox} in the default schema); pass a
 * qualified name such as {@code topology.event_outbox} to isolate per-service tables.
 */
public final class JdbcEventOutbox implements EventOutbox {

    private static final String INSERT_TEMPLATE = """
            INSERT INTO %s
                (event_id, tenant_id, event_type, payload, occurred_at)
            VALUES (?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String SELECT_BY_ID_TEMPLATE = """
            SELECT event_id, tenant_id, event_type, payload::text, attempts, dispatched_at
            FROM %s
            WHERE event_id = ?
            """;

    private static final String SELECT_PENDING_TEMPLATE = """
            SELECT event_id, tenant_id, event_type, payload::text, attempts, dispatched_at
            FROM %s
            WHERE dispatched_at IS NULL
            ORDER BY created_at, event_id
            LIMIT ?
            """;

    private static final String SELECT_PENDING_COUNT_TEMPLATE = """
            SELECT COUNT(*) FROM %s WHERE dispatched_at IS NULL
            """;

    private static final String MARK_DISPATCHED_TEMPLATE = """
            UPDATE %s
            SET dispatched_at = now()
            WHERE event_id = ? AND dispatched_at IS NULL
            """;

    private static final String MARK_FAILED_TEMPLATE = """
            UPDATE %s
            SET attempts = attempts + 1, last_error = ?
            WHERE event_id = ?
            """;

    private final DataSource dataSource;
    private final String insertSql;
    private final String selectByIdSql;
    private final String selectPendingSql;
    private final String selectPendingCountSql;
    private final String markDispatchedSql;
    private final String markFailedSql;

    public JdbcEventOutbox(DataSource dataSource) {
        this(dataSource, "event_outbox");
    }

    public JdbcEventOutbox(DataSource dataSource, String tableName) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        String table = requireTableName(tableName);
        this.insertSql = INSERT_TEMPLATE.formatted(table);
        this.selectByIdSql = SELECT_BY_ID_TEMPLATE.formatted(table);
        this.selectPendingSql = SELECT_PENDING_TEMPLATE.formatted(table);
        this.selectPendingCountSql = SELECT_PENDING_COUNT_TEMPLATE.formatted(table);
        this.markDispatchedSql = MARK_DISPATCHED_TEMPLATE.formatted(table);
        this.markFailedSql = MARK_FAILED_TEMPLATE.formatted(table);
    }

    @Override
    public OutboxEntry append(Connection connection, Event<?> event) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(event, "event");
        try {
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                insert.setObject(1, event.eventId());
                insert.setString(2, event.tenantId());
                insert.setString(3, event.eventType());
                insert.setString(4, json(event));
                insert.setTimestamp(5, Timestamp.from(event.occurredAt()));
                insert.executeUpdate();
            }
            try (PreparedStatement select = connection.prepareStatement(selectByIdSql)) {
                select.setObject(1, event.eventId());
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("outbox row missing after append");
                    }
                    return read(result);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("outbox append failed", exception);
        }
    }

    @Override
    public List<OutboxEntry> pending(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(selectPendingSql)) {
            select.setInt(1, limit);
            try (ResultSet result = select.executeQuery()) {
                List<OutboxEntry> entries = new ArrayList<>();
                while (result.next()) {
                    entries.add(read(result));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("outbox pending query failed", exception);
        }
    }

    @Override
    public long pendingCount() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(selectPendingCountSql)) {
            try (ResultSet result = select.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("outbox pending count query failed", exception);
        }
    }

    @Override
    public Optional<OutboxEntry> find(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(selectByIdSql)) {
            select.setObject(1, eventId);
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("outbox find failed", exception);
        }
    }

    @Override
    public boolean isDispatched(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(selectByIdSql)) {
            select.setObject(1, eventId);
            try (ResultSet result = select.executeQuery()) {
                return result.next() && result.getTimestamp("dispatched_at") != null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("outbox dispatched query failed", exception);
        }
    }

    @Override
    public boolean markDispatched(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(markDispatchedSql)) {
            update.setObject(1, eventId);
            return update.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("outbox mark-dispatched failed", exception);
        }
    }

    @Override
    public boolean markFailed(UUID eventId, String error) {
        Objects.requireNonNull(eventId, "eventId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(markFailedSql)) {
            update.setString(1, error == null ? "" : error);
            update.setObject(2, eventId);
            return update.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("outbox mark-failed failed", exception);
        }
    }

    private static OutboxEntry read(ResultSet result) throws SQLException {
        return new OutboxEntry(
                result.getObject("event_id", UUID.class),
                result.getString("tenant_id"),
                result.getString("event_type"),
                result.getString("payload"),
                result.getInt("attempts"));
    }

    private static String json(Event<?> event) {
        try {
            return EventingJson.newMapper().writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("event is not serializable", exception);
        }
    }

    private static final Pattern SIMPLE = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Pattern QUALIFIED =
            Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z_][a-zA-Z0-9_]*");

    private static String requireTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be blank");
        }
        String trimmed = tableName.trim();
        if (!SIMPLE.matcher(trimmed).matches() && !QUALIFIED.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "tableName must be a simple or schema-qualified SQL identifier: " + trimmed);
        }
        return trimmed;
    }
}
