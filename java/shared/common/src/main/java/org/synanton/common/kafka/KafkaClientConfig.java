package org.synanton.common.kafka;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;
import java.util.function.Function;

/**
 * Creates shared Kafka producer and consumer-factory beans.
 * Only activated when {@code kafka.bootstrap-servers} is set, so services
 * that do not use Kafka are unaffected.
 */
@Configuration
@ConditionalOnProperty(name = "kafka.bootstrap-servers")
public class KafkaClientConfig {

    @Value("${kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${kafka.producer.acks:all}")
    private String producerAcks;

    @Value("${kafka.producer.linger-ms:5}")
    private String lingerMs;

    @Value("${kafka.producer.compression-type:lz4}")
    private String compressionType;

    @Value("${kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${kafka.consumer.session-timeout-ms:30000}")
    private String sessionTimeoutMs;

    @Bean
    public KafkaProducer<String, String> kafkaProducer() {
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        props.put("acks", producerAcks);
        props.put("linger.ms", lingerMs);
        props.put("compression.type", compressionType);
        props.put("enable.idempotence", "true");
        return new KafkaProducer<>(props);
    }

    /**
     * Returns a factory that creates a new {@link KafkaConsumer} bound to the
     * given consumer-group ID. Callers must close the consumer when done.
     */
    @Bean
    public Function<String, KafkaConsumer<String, String>> kafkaConsumerFactory() {
        return groupId -> {
            Properties props = new Properties();
            props.put("bootstrap.servers", bootstrapServers);
            props.put("group.id", groupId);
            props.put("key.deserializer", StringDeserializer.class.getName());
            props.put("value.deserializer", StringDeserializer.class.getName());
            props.put("auto.offset.reset", autoOffsetReset);
            props.put("enable.auto.commit", "false");
            props.put("session.timeout.ms", sessionTimeoutMs);
            return new KafkaConsumer<>(props);
        };
    }
}
