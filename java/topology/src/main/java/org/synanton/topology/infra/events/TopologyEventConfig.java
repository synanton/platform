package org.synanton.topology.infra.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.eventing.contract.EventTypeDeclaration;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.outbox.EventOutbox;
import org.synanton.eventing.outbox.JdbcEventOutbox;
import org.synanton.eventing.outbox.JdbcOutboxTransaction;
import org.synanton.eventing.outbox.KafkaOutboxDispatcher;
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

    private static final Logger log = LoggerFactory.getLogger(TopologyEventConfig.class);

    @Bean
    public EventTypeRegistry topologyEventTypes() {
        return new EventTypeRegistry(List.of(
                new EventTypeDeclaration(
                        TopologyEventPublisher.TENANT_CREATED, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                new EventTypeDeclaration(
                        TopologyEventPublisher.RESOURCE_GRANT_CREATED, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                new EventTypeDeclaration(
                        TopologyEventPublisher.RESOURCE_GRANT_REVOKED, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
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
    public OutboxDispatcher topologyOutboxDispatcher(
            @Value("${eventing.dispatcher.mode:}") String mode,
            @Value("${eventing.kafka.bootstrap-servers:}") String bootstrapServers,
            @Value("${eventing.kafka.topic:topology.events}") String topic) {
        if ("demo".equalsIgnoreCase(mode)) {
            log.warn("NoOpDispatcher active — topology events will not be delivered");
            return new NoOpDispatcher();
        }
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalStateException(
                    "eventing.kafka.bootstrap-servers is required; set eventing.dispatcher.mode=demo for no-op");
        }
        return KafkaOutboxDispatcher.connect(bootstrapServers, topic);
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
