package org.synanton.syntology.infra.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.synanton.eventing.contract.EventTypeDeclaration;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.outbox.EventOutbox;
import org.synanton.eventing.outbox.InMemoryEventOutbox;
import org.synanton.eventing.outbox.JdbcEventOutbox;
import org.synanton.eventing.outbox.JdbcOutboxTransaction;
import org.synanton.eventing.outbox.NoOpDispatcher;
import org.synanton.eventing.outbox.OutboxDispatcher;
import org.synanton.eventing.outbox.OutboxEventPublisher;
import org.synanton.eventing.outbox.OutboxTransaction;
import org.synanton.eventing.publisher.EventPublisher;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Wires the EWP-1 outbox into syntology. The H2 test profile uses an in-memory outbox
 * (the JDBC outbox is PostgreSQL-only); production uses {@link JdbcEventOutbox}. The
 * demo path has no broker, so dispatch is {@link NoOpDispatcher} (outbox rows are written
 * and immediately drained).
 */
@Configuration
public class SyntologyEventConfig {

    @Bean
    public EventTypeRegistry syntologyEventTypes() {
        return new EventTypeRegistry(List.of(
                new EventTypeDeclaration(
                        SyntologyEventPublisher.ONTOLOGY_CREATED, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                new EventTypeDeclaration(
                        SyntologyEventPublisher.VERSION_BUMPED, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                new EventTypeDeclaration(
                        SyntologyEventPublisher.ENTITY_CREATED, 1, ReplayClass.REPLAY_SAFE, Optional.empty())));
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @Profile("!test")
    public EventOutbox jdbcEventOutbox(DataSource dataSource) {
        return new JdbcEventOutbox(dataSource);
    }

    @Bean
    @Profile("test")
    public EventOutbox inMemoryEventOutbox() {
        return new InMemoryEventOutbox();
    }

    @Bean
    public OutboxTransaction outboxTransaction(DataSource dataSource) {
        return new JdbcOutboxTransaction(dataSource);
    }

    @Bean
    public OutboxDispatcher outboxDispatcher() {
        return new NoOpDispatcher();
    }

    @Bean
    public EventPublisher outboxEventPublisher(
            EventTypeRegistry syntologyEventTypes,
            EventOutbox eventOutbox,
            OutboxDispatcher outboxDispatcher,
            OutboxTransaction outboxTransaction) {
        return new OutboxEventPublisher(syntologyEventTypes, eventOutbox, outboxDispatcher, outboxTransaction);
    }
}
