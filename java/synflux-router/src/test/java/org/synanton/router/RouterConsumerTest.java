package org.synanton.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.synanton.common.kafka.IngestJobRequest;

import java.util.UUID;
import java.util.concurrent.Future;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RouterConsumerTest {

    private RouterProperties props;
    private KafkaProducer<String, String> producer;
    private RouterConsumer consumer;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props = new RouterProperties("ingestion_requests", "ingestion_events", 1000, 3, 100L);
        producer = mock(KafkaProducer.class);
        Function<String, org.apache.kafka.clients.consumer.KafkaConsumer<String, String>> factory = mock(Function.class);
        consumer = new RouterConsumer(props, producer, factory);
    }

    @Test
    void pause_addsToSet() {
        consumer.pause("tenant-a");
        assertThat(consumer.getPausedTenants()).contains("tenant-a");
    }

    @Test
    void resume_removeFromSet() {
        consumer.pause("tenant-b");
        consumer.resume("tenant-b");
        assertThat(consumer.getPausedTenants()).doesNotContain("tenant-b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendToEvents_producesWithTenantKey() throws Exception {
        Future<RecordMetadata> future = mock(Future.class);
        when(producer.send(any())).thenReturn(future);
        when(future.get()).thenReturn(null);

        IngestJobRequest req = new IngestJobRequest("demo", UUID.randomUUID(), "filesystem", "/data", 5, "trace-1");
        String json = mapper.writeValueAsString(req);

        ArgumentCaptor<org.apache.kafka.clients.producer.ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(org.apache.kafka.clients.producer.ProducerRecord.class);

        // Access via reflection for white-box test on private method.
        var method = RouterConsumer.class.getDeclaredMethod("sendToEvents", String.class, String.class);
        method.setAccessible(true);
        method.invoke(consumer, "demo", json);

        verify(producer).send(captor.capture());
        assertThat(captor.getValue().key()).isEqualTo("demo");
        assertThat(captor.getValue().topic()).isEqualTo("ingestion_events");
    }
}
