package org.synanton.eventing.outbox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.synanton.eventing.contract.ActorKind;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventTypeDeclaration;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.Producer;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.contract.SecurityMetadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the transactional-outbox wiring: the domain write and the outbox append
 * share one transaction. A failing domain write rolls both back and dispatches
 * nothing (the "business-write rollback" assertion PR 1 flagged as scaffolding).
 */
@Tag("e2e")
class SameTransactionWiringTest extends OutboxTestBase {

    private static final UUID CORRELATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final EventTypeRegistry eventTypes = new EventTypeRegistry(List.of(
            new EventTypeDeclaration("ContentPublished", 1, ReplayClass.REPLAY_SAFE, Optional.empty())));

    @BeforeAll
    static void schema() throws Exception {
        ensureStarted();
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS business_row (
                        id   UUID PRIMARY KEY,
                        name TEXT NOT NULL,
                        CONSTRAINT name_not_boom CHECK (name <> 'boom')
                    )
                    """);
        }
    }

    @BeforeEach
    void resetTables() throws Exception {
        clearOutbox();
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM business_row");
        }
    }

    @Test
    void businessWriteFailure_rollsBackTheOutboxAppendAndDispatchesNothing() throws Exception {
        UUID eventId = UUID.randomUUID();
        JdbcEventOutbox outbox = new JdbcEventOutbox(dataSource());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                eventTypes, outbox, dispatcher, new JdbcOutboxTransaction(dataSource()),
                connection -> businessInsert(connection, UUID.randomUUID(), "boom"));

        assertThatThrownBy(() -> publisher.publish("tenant-a", event(eventId)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(outbox.find(eventId)).isEmpty();
        assertThat(dispatcher.eventIds).isEmpty();
        assertThat(businessRowCount()).isZero();
    }

    @Test
    void outboxAppend_rollsBackWhenABusinessWriteInTheSameTransactionFails() throws Exception {
        UUID eventId = UUID.randomUUID();
        JdbcEventOutbox outbox = new JdbcEventOutbox(dataSource());
        JdbcOutboxTransaction transaction = new JdbcOutboxTransaction(dataSource());

        assertThatThrownBy(() -> transaction.execute(connection -> {
            OutboxEntry entry = outbox.append(connection, event(eventId));
            businessInsert(connection, UUID.randomUUID(), "boom");
            return entry;
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outbox.find(eventId)).isEmpty();
        assertThat(businessRowCount()).isZero();
    }

    @Test
    void businessWriteAndOutboxAppend_commitTogetherThenDispatch() throws Exception {
        UUID eventId = UUID.randomUUID();
        JdbcEventOutbox outbox = new JdbcEventOutbox(dataSource());
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                eventTypes, outbox, dispatcher, new JdbcOutboxTransaction(dataSource()),
                connection -> businessInsert(connection, UUID.randomUUID(), "ok"));

        publisher.publish("tenant-a", event(eventId));

        assertThat(businessRowCount()).isEqualTo(1);
        assertThat(outbox.find(eventId)).isPresent();
        assertThat(outbox.isDispatched(eventId)).isTrue();
        assertThat(dispatcher.eventIds).containsExactly(eventId);
    }

    private static void businessInsert(Connection connection, UUID id, String name) {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO business_row (id, name) VALUES (?, ?)")) {
            insert.setObject(1, id);
            insert.setString(2, name);
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("business write failed", e);
        }
    }

    private static long businessRowCount() throws Exception {
        try (Connection connection = connection();
                PreparedStatement select = connection.prepareStatement("SELECT count(*) FROM business_row");
                ResultSet result = select.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Event<String> event(UUID eventId) {
        return new Event<>(
                eventId,
                "ContentPublished",
                1,
                Instant.parse("2026-10-26T12:00:00Z"),
                new Producer("content-cache", "1.26"),
                "tenant-a",
                Optional.empty(),
                CORRELATION_ID,
                Optional.empty(),
                ReplayClass.REPLAY_SAFE,
                Optional.empty(),
                new SecurityMetadata(ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of()),
                "payload");
    }

    private static final class RecordingDispatcher implements OutboxDispatcher {

        private final List<UUID> eventIds = new ArrayList<>();

        @Override
        public void dispatch(OutboxEntry entry) {
            eventIds.add(entry.eventId());
        }
    }
}
