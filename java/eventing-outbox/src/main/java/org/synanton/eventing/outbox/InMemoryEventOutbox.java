package org.synanton.eventing.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventingJson;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Test double with the same idempotency and retention behavior as the JDBC outbox. */
public final class InMemoryEventOutbox implements EventOutbox {

    private final Map<UUID, StoredEntry> rows = new LinkedHashMap<>();

    @Override
    public synchronized OutboxEntry append(Connection connection, Event<?> event) {
        Objects.requireNonNull(event, "event");
        StoredEntry existing = rows.get(event.eventId());
        if (existing != null) {
            return existing.entry();
        }
        OutboxEntry entry = new OutboxEntry(
                event.eventId(),
                event.tenantId(),
                event.eventType(),
                json(event),
                0);
        rows.put(entry.eventId(), new StoredEntry(entry, false));
        return entry;
    }

    @Override
    public synchronized List<OutboxEntry> pending(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<OutboxEntry> pending = new ArrayList<>();
        for (StoredEntry stored : rows.values()) {
            if (!stored.dispatched()) {
                pending.add(stored.entry());
                if (pending.size() == limit) {
                    break;
                }
            }
        }
        return List.copyOf(pending);
    }

    @Override
    public synchronized Optional<OutboxEntry> find(UUID eventId) {
        StoredEntry stored = rows.get(Objects.requireNonNull(eventId, "eventId"));
        return stored == null ? Optional.empty() : Optional.of(stored.entry());
    }

    @Override
    public synchronized boolean isDispatched(UUID eventId) {
        StoredEntry stored = rows.get(Objects.requireNonNull(eventId, "eventId"));
        return stored != null && stored.dispatched();
    }

    @Override
    public synchronized boolean markDispatched(UUID eventId) {
        StoredEntry stored = rows.get(Objects.requireNonNull(eventId, "eventId"));
        if (stored == null || stored.dispatched()) {
            return false;
        }
        rows.put(eventId, new StoredEntry(stored.entry(), true));
        return true;
    }

    @Override
    public synchronized boolean markFailed(UUID eventId, String error) {
        StoredEntry stored = rows.get(Objects.requireNonNull(eventId, "eventId"));
        if (stored == null) {
            return false;
        }
        OutboxEntry current = stored.entry();
        rows.put(eventId, new StoredEntry(
                new OutboxEntry(
                        current.eventId(),
                        current.tenantId(),
                        current.eventType(),
                        current.payloadJson(),
                        current.attempts() + 1),
                stored.dispatched()));
        return true;
    }

    private static String json(Event<?> event) {
        try {
            return EventingJson.newMapper().writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("event is not serializable", exception);
        }
    }

    private record StoredEntry(OutboxEntry entry, boolean dispatched) {
    }
}
