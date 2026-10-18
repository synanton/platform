package org.synanton.ingestioncache.outbox;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.SourceOutboxRow;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    @Test
    void publishesSourceEventWithStableIdentityAndAcknowledgesOnlyAfterBrokerAck() throws Exception {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        SourceOutboxRow event = event("tenant-not-demo", 0);
        when(cache.listTenantIds()).thenReturn(List.of("tenant-not-demo"));
        when(cache.listUnpublishedOutbox("tenant-not-demo", 100, NOW)).thenReturn(List.of());
        when(cache.listUnpublishedSourceOutbox("tenant-not-demo", 100, NOW)).thenReturn(List.of(event));
        when(producer.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        new OutboxPublisher(cache, producer, Clock.fixed(NOW, ZoneOffset.UTC)).publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> published = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(published.capture());
        assertThat(published.getValue().topic()).isEqualTo("source_events");
        assertThat(published.getValue().key()).isEqualTo("tenant-not-demo:file:source-a:guide.md");
        assertThat(published.getValue().value()).contains(event.eventId().toString());
        verify(cache).markSourceOutboxPublished(event, NOW);
    }

    @Test
    void retryableSourcePublicationFailurePersistsBackoffAndDeadLettersAfterLimit() throws Exception {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        SourceOutboxRow retryableEvent = event("tenant-a", 0);
        SourceOutboxRow finalAttempt = event("tenant-a", 4);
        when(cache.listTenantIds()).thenReturn(List.of("tenant-a"));
        when(cache.listUnpublishedOutbox("tenant-a", 100, NOW)).thenReturn(List.of());
        when(cache.listUnpublishedSourceOutbox("tenant-a", 100, NOW))
            .thenReturn(List.of(retryableEvent))
            .thenReturn(List.of(finalAttempt));
        CompletableFuture<RecordMetadata> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("broker unavailable"));
        when(producer.send(any(ProducerRecord.class))).thenReturn(failed);
        OutboxPublisher publisher = new OutboxPublisher(cache, producer, Clock.fixed(NOW, ZoneOffset.UTC));

        publisher.publishPending();
        publisher.publishPending();

        verify(cache).recordSourceOutboxFailure(
            eq(retryableEvent), eq(1), contains("broker unavailable"), eq(NOW.plusSeconds(1)), eq(false), eq(NOW));
        verify(cache).recordSourceOutboxFailure(
            eq(finalAttempt), eq(5), contains("broker unavailable"), eq(null), eq(true), eq(NOW));
        verify(cache, never()).markSourceOutboxPublished(any(), any());
    }

    @Test
    void permanentSerializationFailureDeadLettersWithoutRetry() throws Exception {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        SourceOutboxRow event = event("tenant-a", 0);
        when(cache.listTenantIds()).thenReturn(List.of("tenant-a"));
        when(cache.listUnpublishedOutbox("tenant-a", 100, NOW)).thenReturn(List.of());
        when(cache.listUnpublishedSourceOutbox("tenant-a", 100, NOW)).thenReturn(List.of(event));
        CompletableFuture<RecordMetadata> failed = new CompletableFuture<>();
        failed.completeExceptionally(new SerializationException("invalid event payload"));
        when(producer.send(any(ProducerRecord.class))).thenReturn(failed);

        new OutboxPublisher(cache, producer, Clock.fixed(NOW, ZoneOffset.UTC)).publishPending();

        verify(cache).recordSourceOutboxFailure(
            eq(event), eq(1), contains("invalid event payload"), eq(null), eq(true), eq(NOW));
    }

    private static SourceOutboxRow event(String tenantId, int attemptCount) {
        UUID eventId = UUID.randomUUID();
        return new SourceOutboxRow(
            tenantId, "file", "source-a", "guide.md", eventId, "SourcePublished",
            UUID.randomUUID(), null, "{\"event_id\":\"" + eventId + "\"}", NOW, false,
            null, attemptCount, null, null, false);
    }
}
