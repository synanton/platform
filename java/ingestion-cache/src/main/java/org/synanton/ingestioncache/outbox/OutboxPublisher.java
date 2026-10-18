package org.synanton.ingestioncache.outbox;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.OutboxRow;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls the ingestion-cache outboxes and publishes at least once. Failures retain
 * the source event identity, update retry state, and dead-letter after bounded retries.
 */
@Component
@ConditionalOnBean(type = "org.apache.kafka.clients.producer.KafkaProducer")
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int POLL_INTERVAL_MS = 2_000;
    private static final int BATCH_LIMIT = 100;
    private static final int MAX_ATTEMPTS = 5;

    private final IngestionCacheClient cacheClient;
    private final KafkaProducer<String, String> producer;
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "outbox-publisher"); t.setDaemon(true); return t; }
    );

    public OutboxPublisher(IngestionCacheClient cacheClient, KafkaProducer<String, String> producer) {
        this(cacheClient, producer, Clock.systemUTC());
    }

    OutboxPublisher(IngestionCacheClient cacheClient, KafkaProducer<String, String> producer, Clock clock) {
        this.cacheClient = cacheClient;
        this.producer = producer;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(this::publishPending, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("OutboxPublisher started (interval={}ms)", POLL_INTERVAL_MS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    void publishPending() {
        for (String tenantId : cacheClient.listTenantIds()) {
            try {
                publishManifestTransitions(tenantId);
                publishSourceEvents(tenantId);
            } catch (Exception e) {
                log.warn("OutboxPublisher error for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void publishManifestTransitions(String tenantId) {
        List<OutboxRow> pending = cacheClient.listUnpublishedOutbox(tenantId, BATCH_LIMIT, clock.instant());
        for (OutboxRow row : pending) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        row.topic(), tenantId, row.payloadJson()
                );
                producer.send(record).get();
                cacheClient.markOutboxPublished(tenantId, row.eventId(), clock.instant());
                log.debug("Outbox row published: topic={} tenant={}", row.topic(), tenantId);
            } catch (Exception e) {
                recordManifestFailure(row, e);
            }
        }
        producer.flush();
    }

    private void publishSourceEvents(String tenantId) {
        List<SourceOutboxRow> pending = cacheClient.listUnpublishedSourceOutbox(
            tenantId, BATCH_LIMIT, clock.instant());
        for (SourceOutboxRow event : pending) {
            try {
                String key = tenantId + ":" + event.connectorId() + ":" + event.sourceId() + ":" + event.resourceId();
                producer.send(new ProducerRecord<>("source_events", key, event.payloadJson())).get();
                cacheClient.markSourceOutboxPublished(event, clock.instant());
            } catch (Exception failure) {
                int attempts = event.attemptCount() + 1;
                boolean deadLettered = !retryable(failure) || attempts >= MAX_ATTEMPTS;
                Instant nextAttemptAt = deadLettered ? null : clock.instant().plusSeconds(backoffSeconds(attempts));
                cacheClient.recordSourceOutboxFailure(
                    event, attempts, describe(failure), nextAttemptAt, deadLettered, clock.instant());
                log.warn("Failed to publish source event {} (attempt {}, deadLettered={}): {}",
                    event.eventId(), attempts, deadLettered, failure.toString());
            }
        }
        producer.flush();
    }

    private void recordManifestFailure(OutboxRow row, Exception failure) {
        int attempts = row.attemptCount() + 1;
        boolean deadLettered = !retryable(failure) || attempts >= MAX_ATTEMPTS;
        Instant now = clock.instant();
        Instant nextAttemptAt = deadLettered ? null : now.plusSeconds(backoffSeconds(attempts));
        cacheClient.recordOutboxFailure(row, attempts, describe(failure), nextAttemptAt, deadLettered, now);
        log.warn("Failed to publish manifest event {} (attempt {}, deadLettered={}): {}",
            row.eventId(), attempts, deadLettered, failure.toString());
    }

    private static long backoffSeconds(int attempts) {
        return Math.min(1L << Math.min(Math.max(attempts - 1, 0), 8), 300);
    }

    private static boolean retryable(Exception failure) {
        Throwable cause = unwrap(failure);
        return !(cause instanceof InvalidTopicException
            || cause instanceof RecordTooLargeException
            || cause instanceof SerializationException);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Exception failure) {
        Throwable cause = failure instanceof ExecutionException && failure.getCause() != null
            ? failure.getCause() : failure;
        String message = cause.getMessage();
        String description = cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return description.substring(0, Math.min(description.length(), 512));
    }
}
