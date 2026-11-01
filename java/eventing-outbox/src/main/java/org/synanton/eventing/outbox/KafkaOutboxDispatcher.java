package org.synanton.eventing.outbox;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

/**
 * Real dispatch seam backed by a Kafka-protocol broker (Redpanda in Compose).
 * The broker acknowledgement is awaited before {@code dispatch} returns, so the
 * caller marks a row dispatched only after the broker has accepted the record.
 */
public final class KafkaOutboxDispatcher implements OutboxDispatcher, AutoCloseable {

    private final KafkaProducer<String, String> producer;
    private final String topic;

    public KafkaOutboxDispatcher(KafkaProducer<String, String> producer, String topic) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.topic = requireText(topic, "topic");
    }

    /**
     * Builds an idempotent producer (acks=all) for the given bootstrap servers and topic.
     * The caller owns the returned dispatcher and must close it when finished.
     */
    public static KafkaOutboxDispatcher connect(String bootstrapServers, String topic) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", requireText(bootstrapServers, "bootstrapServers"));
        properties.put("key.serializer", StringSerializer.class.getName());
        properties.put("value.serializer", StringSerializer.class.getName());
        properties.put("acks", "all");
        properties.put("enable.idempotence", "true");
        return new KafkaOutboxDispatcher(new KafkaProducer<>(properties), topic);
    }

    @Override
    public void dispatch(OutboxEntry entry) {
        Objects.requireNonNull(entry, "entry");
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, entry.tenantId(), entry.payloadJson());
        try {
            producer.send(record).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("dispatch interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("broker dispatch failed", failure.getCause());
        }
    }

    @Override
    public void close() {
        producer.close();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
