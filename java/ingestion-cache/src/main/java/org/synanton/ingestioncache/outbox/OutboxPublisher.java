package org.synanton.ingestioncache.outbox;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.OutboxRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls {@code manifest_transitions_outbox} every 2 seconds and publishes
 * unpublished rows to their target Kafka topic. Provides at-least-once delivery:
 * on producer failure the row stays unpublished and is retried on the next cycle.
 */
@Component
@ConditionalOnBean(KafkaProducer.class)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int POLL_INTERVAL_MS = 2_000;
    private static final int BATCH_LIMIT = 100;

    private final IngestionCacheClient cacheClient;
    private final KafkaProducer<String, String> producer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "outbox-publisher"); t.setDaemon(true); return t; }
    );

    public OutboxPublisher(IngestionCacheClient cacheClient, KafkaProducer<String, String> producer) {
        this.cacheClient = cacheClient;
        this.producer = producer;
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
        // Iterate over all tenants that have pending outbox rows.
        // In Phase 3 (single demo tenant) we query with a known tenant;
        // a production implementation would track tenant IDs separately.
        List<String> tenants = List.of("demo", "demo2");
        for (String tenantId : tenants) {
            try {
                publishForTenant(tenantId);
            } catch (Exception e) {
                log.warn("OutboxPublisher error for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void publishForTenant(String tenantId) {
        List<OutboxRow> pending = cacheClient.listUnpublishedOutbox(tenantId, BATCH_LIMIT);
        if (pending.isEmpty()) return;

        for (OutboxRow row : pending) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        row.topic(), tenantId, row.payloadJson()
                );
                producer.send(record).get();
                cacheClient.markOutboxPublished(tenantId, row.eventId());
                log.debug("Outbox row published: topic={} tenant={}", row.topic(), tenantId);
            } catch (Exception e) {
                log.warn("Failed to publish outbox row for tenant {}: {}", tenantId, e.getMessage());
            }
        }
        producer.flush();
    }
}
