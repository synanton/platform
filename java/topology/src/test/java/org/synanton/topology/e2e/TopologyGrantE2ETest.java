package org.synanton.topology.e2e;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.synanton.eventing.contract.EventTypeDeclaration;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.outbox.JdbcEventOutbox;
import org.synanton.eventing.outbox.JdbcOutboxTransaction;
import org.synanton.eventing.outbox.KafkaOutboxDispatcher;
import org.synanton.eventing.outbox.OutboxEventPublisher;
import org.synanton.eventing.outbox.OutboxReader;
import org.synanton.topology.domain.GrantMutationService;
import org.synanton.topology.domain.event.GrantCreated;
import org.synanton.topology.domain.event.TenantCreated;
import org.synanton.topology.domain.model.Tenant;
import org.synanton.topology.infra.events.TopologyEventPublisher;
import org.synanton.topology.infra.jdbc.JdbcGrantMutationStore;
import org.synanton.topology.infra.jdbc.JdbcTenantRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof: a grant publishes through the real JDBC outbox to Redpanda and back,
 * with the grant write and outbox append in one transaction and the facade-returned event_id
 * matching the consumed event.
 */
@Tag("e2e")
class TopologyGrantE2ETest {

    private static final String TOPIC = "topology.events";
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final String REDPANDA_IMAGE = "redpandadata/redpanda:v24.3.5";

    private static PostgreSQLContainer<?> postgres;
    private static RedpandaContainer redpanda;
    private static KafkaOutboxDispatcher dispatcher;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE));
        postgres.start();
        applySchema();
        redpanda = new RedpandaContainer(DockerImageName.parse(REDPANDA_IMAGE));
        redpanda.start();
        dispatcher = KafkaOutboxDispatcher.connect(redpanda.getBootstrapServers(), TOPIC);
    }

    @AfterAll
    static void tearDown() {
        if (dispatcher != null) {
            dispatcher.close();
        }
        if (redpanda != null) {
            redpanda.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void grant_publishesThroughTheOutboxToRedpandaAndConsumesMatchingEventId() throws Exception {
        JdbcEventOutbox outbox = new JdbcEventOutbox(dataSource(), "topology.event_outbox");
        TopologyEventPublisher events = publisher(outbox, dispatcher);
        JdbcGrantMutationStore grantStore = new JdbcGrantMutationStore(new JdbcTemplate(dataSource()), new com.fasterxml.jackson.databind.ObjectMapper());

        GrantMutationService.GrantCommand command = command();
        UUID eventId = events.publishGrantCreated(
                command.tenantId(),
                new GrantCreated(command.tenantId(), command.subjectId(), command.resourceId(), command.permission()),
                connection -> grantStore.insertPending(connection, command, Instant.now()));

        assertThat(outbox.isDispatched(eventId)).isTrue();
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM topology.acl_grants WHERE tenant_id = ?", Integer.class, command.tenantId()))
                .isEqualTo(1);

        ConsumerRecord<String, String> consumed = consume(eventId);
        assertThat(consumed.value()).contains(eventId.toString());
        assertThat(consumed.value()).contains("GrantCreated");
    }

    @Test
    void tenantCreated_publishesAndConsumes() {
        JdbcEventOutbox outbox = new JdbcEventOutbox(dataSource(), "topology.event_outbox");
        TopologyEventPublisher events = publisher(outbox, dispatcher);
        JdbcTenantRepository tenants = new JdbcTenantRepository(new JdbcTemplate(dataSource()));
        String tenantId = "tenant-b";

        UUID eventId = events.publishTenantCreated(
                tenantId,
                new TenantCreated(tenantId, "Tenant B", "owner-1"),
                connection -> tenants.insert(connection, new Tenant(tenantId, "Tenant B", Instant.now())));

        assertThat(outbox.isDispatched(eventId)).isTrue();
        assertThat(jdbc().queryForObject(
                "SELECT COUNT(*) FROM topology.tenants WHERE tenant_id = ?", Integer.class, tenantId)).isEqualTo(1);
        assertThat(consume(eventId).value()).contains("TenantCreated");
    }

    @Test
    void dispatchFailure_leavesTheRowPendingAndTheReaderRetriesIt() throws Exception {
        JdbcEventOutbox outbox = new JdbcEventOutbox(dataSource(), "topology.event_outbox");
        TopologyEventPublisher failing = publisher(outbox, entry -> {
            throw new IllegalStateException("broker down");
        });
        JdbcGrantMutationStore grantStore = new JdbcGrantMutationStore(new JdbcTemplate(dataSource()), new com.fasterxml.jackson.databind.ObjectMapper());

        GrantMutationService.GrantCommand command = command();
        UUID eventId;
        try {
            eventId = failing.publishGrantCreated(
                    command.tenantId(),
                    new GrantCreated(command.tenantId(), command.subjectId(), command.resourceId(), command.permission()),
                    connection -> grantStore.insertPending(connection, command, Instant.now()));
        } catch (IllegalStateException expected) {
            eventId = pendingEventId(command.tenantId());
        }

        assertThat(outbox.isDispatched(eventId)).isFalse();

        OutboxReader reader = new OutboxReader(outbox, dispatcher, 10);
        assertThat(reader.drain()).isEqualTo(1);
        reader.close();

        assertThat(outbox.isDispatched(eventId)).isTrue();
        assertThat(consume(eventId).value()).contains(eventId.toString());
    }

    private static TopologyEventPublisher publisher(JdbcEventOutbox outbox, org.synanton.eventing.outbox.OutboxDispatcher dispatcher) {
        EventTypeRegistry registry = new EventTypeRegistry(List.of(
                new EventTypeDeclaration(TopologyEventPublisher.TENANT_CREATED, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                new EventTypeDeclaration(TopologyEventPublisher.GRANT_CREATED, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                new EventTypeDeclaration(TopologyEventPublisher.CLASS_GRANT_UPSERT, 1, ReplayClass.REPLAY_SAFE, Optional.empty()),
                new EventTypeDeclaration(TopologyEventPublisher.CLASS_GRANT_REVOKE, 1, ReplayClass.REPLAY_SAFE, Optional.empty())));
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                registry, outbox, dispatcher, new JdbcOutboxTransaction(dataSource()));
        return new TopologyEventPublisher(publisher, Clock.systemUTC());
    }

    private static UUID pendingEventId(String tenantId) {
        return jdbc().queryForObject(
                "SELECT event_id FROM topology.event_outbox WHERE tenant_id = ? AND dispatched_at IS NULL",
                UUID.class, tenantId);
    }

    private static ConsumerRecord<String, String> consume(UUID eventId) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", redpanda.getBootstrapServers());
        properties.put("group.id", "topology-e2e-" + UUID.randomUUID());
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", StringDeserializer.class.getName());
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
            while (Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value() != null && record.value().contains(eventId.toString())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("event " + eventId + " was not consumed within the deadline");
    }

    private static GrantMutationService.GrantCommand command() {
        return new GrantMutationService.GrantCommand(
                "tenant-a", "user:alice", "USER",
                "doc/ontology.ttl", "DOCUMENT", "READ", "k1",
                "admin", "USER_SUBJECT", "user");
    }

    private static DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    private static void applySchema() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE SCHEMA topology");
        jdbc.execute("""
                CREATE TABLE topology.tenants (
                    tenant_id    TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    created_at   TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE topology.acl_grants (
                    grant_id          UUID PRIMARY KEY,
                    org_id            UUID NOT NULL,
                    subject_id        UUID NOT NULL,
                    subject_type      TEXT NOT NULL,
                    resource_path     TEXT NOT NULL,
                    permission        TEXT NOT NULL,
                    source            TEXT NOT NULL,
                    tenant_id         TEXT,
                    propagation_state TEXT NOT NULL,
                    resource_id       TEXT,
                    resource_type     TEXT,
                    subject_key       TEXT,
                    created_at        TIMESTAMPTZ
                )
                """);
        jdbc.execute("""
                CREATE TABLE topology.event_outbox (
                    event_id      UUID PRIMARY KEY,
                    tenant_id     TEXT NOT NULL,
                    event_type    TEXT NOT NULL,
                    payload       JSONB NOT NULL,
                    occurred_at   TIMESTAMPTZ NOT NULL,
                    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                    dispatched_at TIMESTAMPTZ,
                    attempts      INTEGER NOT NULL DEFAULT 0,
                    last_error    TEXT
                )
                """);
    }
}
