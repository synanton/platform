package org.synanton.eventing.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.synanton.eventing.contract.ActorKind;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventTypeDeclaration;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.EventingJson;
import org.synanton.eventing.contract.Producer;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.contract.SecurityMetadata;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end publish -> Redpanda -> consume over a real Postgres outbox and a
 * real broker, separated from the unit suite behind the {@code e2e} tag.
 */
@Tag("e2e")
class OutboxE2ETest extends OutboxTestBase {

    private static final String REDPANDA_IMAGE = "redpandadata/redpanda:v24.3.5";
    private static final String TOPIC = "synanton.events";
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CRASHED_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CORRELATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static RedpandaContainer redpanda;
    private static KafkaOutboxDispatcher dispatcher;

    private final EventTypeRegistry eventTypes = new EventTypeRegistry(List.of(
            new EventTypeDeclaration("ContentPublished", 1, ReplayClass.REPLAY_SAFE, Optional.empty())));

    @BeforeAll
    static void broker() {
        ensureStarted();
        try {
            redpanda = new RedpandaContainer(DockerImageName.parse(REDPANDA_IMAGE));
            redpanda.start();
        } catch (RuntimeException e) {
            redpanda = null;
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false, "redpanda container unavailable, suite skipped: " + e.getMessage());
        }
        dispatcher = KafkaOutboxDispatcher.connect(redpanda.getBootstrapServers(), TOPIC);
    }

    @AfterAll
    static void teardown() {
        if (dispatcher != null) {
            dispatcher.close();
        }
        if (redpanda != null) {
            redpanda.stop();
        }
    }

    @BeforeEach
    void resetOutbox() throws Exception {
        clearOutbox();
    }

    @Test
    void publish_dispatchesToRedpandaAndConsumesTheSameEnvelope() throws Exception {
        OutboxEventPublisher publisher = new OutboxEventPublisher(
                eventTypes,
                new JdbcEventOutbox(dataSource()),
                dispatcher,
                new JdbcOutboxTransaction(dataSource()));

        publisher.publish("tenant-a", event(EVENT_ID));

        ConsumerRecord<String, String> consumed = consume(EVENT_ID);
        assertThat(consumed.key()).isEqualTo("tenant-a");

        Event<String> roundTripped = EventingJson.readEvent(
                consumed.value().getBytes(StandardCharsets.UTF_8),
                new TypeReference<Event<String>>() { },
                eventTypes);
        assertThat(roundTripped.eventId()).isEqualTo(EVENT_ID);
        assertThat(roundTripped.eventType()).isEqualTo("ContentPublished");
        assertThat(roundTripped.tenantId()).isEqualTo("tenant-a");
    }

    @Test
    void readerLoop_drainsARowLeftByACrashedPublisherAndConsumesIt() throws Exception {
        JdbcEventOutbox outbox = new JdbcEventOutbox(dataSource());
        JdbcOutboxTransaction transaction = new JdbcOutboxTransaction(dataSource());
        transaction.execute(connection -> outbox.append(connection, event(CRASHED_ID)));

        assertThat(outbox.pending(10)).extracting(OutboxEntry::eventId).containsExactly(CRASHED_ID);

        OutboxReader reader = new OutboxReader(outbox, dispatcher, 10);
        assertThat(reader.drain()).isEqualTo(1);
        reader.close();

        assertThat(outbox.isDispatched(CRASHED_ID)).isTrue();
        assertThat(consume(CRASHED_ID).key()).isEqualTo("tenant-a");
    }

    private static ConsumerRecord<String, String> consume(UUID eventId) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", redpanda.getBootstrapServers());
        properties.put("group.id", "outbox-e2e-" + UUID.randomUUID());
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
}
