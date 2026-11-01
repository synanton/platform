package org.synanton.eventing.outbox;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class KafkaOutboxDispatcherTest {

    private static final String TOPIC = "synanton.events";
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void dispatch_sendsTheDurablePayloadToTheTopicKeyedByTenant() {
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        when(producer.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        KafkaOutboxDispatcher dispatcher = new KafkaOutboxDispatcher(producer, TOPIC);

        dispatcher.dispatch(entry());

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo(TOPIC);
        assertThat(sent.getValue().key()).isEqualTo("tenant-a");
        assertThat(sent.getValue().value())
                .contains("\"event_id\":\"11111111-1111-4111-8111-111111111111\"");
    }

    @Test
    void dispatch_returnsOnlyAfterTheBrokerAcknowledges() throws Exception {
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        CountDownLatch acknowledged = new CountDownLatch(1);
        CompletableFuture<RecordMetadata> pending = new CompletableFuture<>();
        when(producer.send(any(ProducerRecord.class))).thenReturn(pending);
        KafkaOutboxDispatcher dispatcher = new KafkaOutboxDispatcher(producer, TOPIC);

        Thread dispatchThread = new Thread(() -> {
            dispatcher.dispatch(entry());
            acknowledged.countDown();
        });
        dispatchThread.start();

        assertThat(acknowledged.await(500, TimeUnit.MILLISECONDS))
                .as("dispatch must not return before the broker ack")
                .isFalse();

        pending.complete(null);
        assertThat(acknowledged.await(2, TimeUnit.SECONDS))
                .as("dispatch must return once the broker acks")
                .isTrue();
        dispatchThread.join(2_000);
    }

    @Test
    void dispatchFailure_propagatesSoTheRowStaysPending() {
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        CompletableFuture<RecordMetadata> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker down"));
        when(producer.send(any(ProducerRecord.class))).thenReturn(failed);
        KafkaOutboxDispatcher dispatcher = new KafkaOutboxDispatcher(producer, TOPIC);

        assertThatThrownBy(() -> dispatcher.dispatch(entry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker dispatch failed")
                .hasRootCauseMessage("broker down");
    }

    private static OutboxEntry entry() {
        return new OutboxEntry(
                EVENT_ID,
                "tenant-a",
                "ContentPublished",
                "{\"event_id\":\"11111111-1111-4111-8111-111111111111\",\"event_type\":\"ContentPublished\"}",
                0);
    }
}
