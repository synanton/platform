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

/** PostgreSQL outbox. Append uses the caller's connection and never commits or closes it. */
public final class JdbcEventOutbox implements EventOutbox {

    private static final String INSERT = """
            INSERT INTO event_outbox
                (event_id, tenant_id, event_type, payload, occurred_at)
            VALUES (?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String SELECT_BY_ID = """
            SELECT event_id, tenant_id, event_type, payload::text, attempts, dispatched_at
            FROM event_outbox
            WHERE event_id = ?
            """;

    private static final String SELECT_PENDING = """
            SELECT event_id, tenant_id, event_type, payload::text, attempts, dispatched_at
            FROM event_outbox
            WHERE dispatched_at IS NULL
            ORDER BY created_at, event_id
            LIMIT ?
            """;

    private static final String MARK_DISPATCHED = """
            UPDATE event_outbox
            SET dispatched_at = now()
            WHERE event_id = ? AND dispatched_at IS NULL
            """;

    private static final String MARK_FAILED = """
            UPDATE event_outbox
            SET attempts = attempts + 1, last_error = ?
            WHERE event_id = ?
            """;

    private final DataSource dataSource;

    public JdbcEventOutbox(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public OutboxEntry append(Connection connection, Event<?> event) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(event, "event");
        try {
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                insert.setObject(1, event.eventId());
                insert.setString(2, event.tenantId());
                insert.setString(3, event.eventType());
                insert.setString(4, json(event));
                insert.setTimestamp(5, Timestamp.from(event.occurredAt()));
                insert.executeUpdate();
            }
            try (PreparedStatement select = connection.prepareStatement(SELECT_BY_ID)) {
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
             PreparedStatement select = connection.prepareStatement(SELECT_PENDING)) {
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
    public Optional<OutboxEntry> find(UUID eventId) {
        Objects.requireNonNull(eventId, "eventId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(SELECT_BY_ID)) {
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
             PreparedStatement select = connection.prepareStatement(SELECT_BY_ID)) {
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
             PreparedStatement update = connection.prepareStatement(MARK_DISPATCHED)) {
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
             PreparedStatement update = connection.prepareStatement(MARK_FAILED)) {
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
}
