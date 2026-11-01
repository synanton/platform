package org.synanton.topology.infra.events;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.eventing.contract.EventTypeDeclaration;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.outbox.EventOutbox;
import org.synanton.eventing.outbox.JdbcEventOutbox;
import org.synanton.eventing.outbox.JdbcOutboxTransaction;
import org.synanton.eventing.outbox.NoOpDispatcher;
import org.synanton.eventing.outbox.OutboxDispatcher;
import org.synanton.eventing.outbox.OutboxEventPublisher;
import org.synanton.eventing.outbox.OutboxTransaction;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * Wires the EWP-1 outbox into topology, writing to its own per-service table
 * {@code topology.event_outbox}. The demo path has no broker, so dispatch is a no-op
 * (rows are written and immediately retired); a real {@code KafkaOutboxDispatcher} is
 * wired in the e2e test.
 */
@Configuration
public class TopologyEventConfig {

    @Bean
    public EventTypeRegistry topologyEventTypes() {
        return new EventTypeRegistry(List.of(
                new EventTypeDeclaration(
                        TopologyEventPublisher.GRANT_CREATED, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                new EventTypeDeclaration(
                        TopologyEventPublisher.CLASS_GRANT_UPSERT, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                new EventTypeDeclaration(
                        TopologyEventPublisher.CLASS_GRANT_REVOKE, 1, ReplayClass.REPLAY_SAFE, Optional.empty())));
    }

    @Bean
    public EventOutbox topologyEventOutbox(DataSource dataSource) {
        return new JdbcEventOutbox(dataSource, "topology.event_outbox");
    }

    @Bean
    public OutboxTransaction topologyOutboxTransaction(DataSource dataSource) {
        return new JdbcOutboxTransaction(dataSource);
    }

    @Bean
    public OutboxDispatcher topologyOutboxDispatcher() {
        return new NoOpDispatcher();
    }

    @Bean
    public OutboxEventPublisher topologyOutboxEventPublisher(
            EventTypeRegistry topologyEventTypes,
            EventOutbox topologyEventOutbox,
            OutboxDispatcher topologyOutboxDispatcher,
            OutboxTransaction topologyOutboxTransaction) {
        return new OutboxEventPublisher(
                topologyEventTypes, topologyEventOutbox, topologyOutboxDispatcher, topologyOutboxTransaction);
    }
}
