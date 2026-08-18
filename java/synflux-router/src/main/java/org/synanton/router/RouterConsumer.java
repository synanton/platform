package org.synanton.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.synanton.common.kafka.IngestJobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Core routing component: consumes {@code ingestion_requests}, partitions each
 * message by {@code tenantId} and produces to {@code ingestion_events}.
 *
 * <p>Pause/resume: paused tenants have their messages buffered in a bounded
 * in-memory queue. When the queue reaches {@code pauseQueueMaxSize} the
 * corresponding Kafka partition is paused to apply backpressure.
 */
@Component
public class RouterConsumer {

    private static final Logger log = LoggerFactory.getLogger(RouterConsumer.class);
    private static final String CONSUMER_GROUP = "synflux-router";

    private final RouterProperties props;
    private final KafkaProducer<String, String> producer;
    private final Function<String, KafkaConsumer<String, String>> consumerFactory;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Set<String> pausedTenants = ConcurrentHashMap.newKeySet();
    private final Map<String, Queue<String>> pauseQueues = new ConcurrentHashMap<>();
    private final LongAdder dropTotal = new LongAdder();

    private KafkaConsumer<String, String> consumer;
    private ExecutorService executor;
    private volatile boolean running = true;

    // Lag snapshots updated every 30 s.
    private volatile Map<String, Long> lagSnapshot = new LinkedHashMap<>();

    public RouterConsumer(
            RouterProperties props,
            KafkaProducer<String, String> producer,
            Function<String, KafkaConsumer<String, String>> kafkaConsumerFactory
    ) {
        this.props = props;
        this.producer = producer;
        this.consumerFactory = kafkaConsumerFactory;
    }

    @PostConstruct
    void start() {
        consumer = consumerFactory.apply(CONSUMER_GROUP);
        consumer.subscribe(List.of(props.ingestionRequestsTopic()));
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "synflux-router");
            t.setDaemon(true);
            return t;
        });
        executor.submit(this::pollLoop);
        log.info("RouterConsumer started: {} → {}", props.ingestionRequestsTopic(), props.ingestionEventsTopic());
    }

    @PreDestroy
    void stop() {
        running = false;
        executor.shutdownNow();
        if (consumer != null) {
            try { consumer.wakeup(); } catch (Exception ignored) {}
        }
    }

    // ---- Pause / resume ----

    public void pause(String tenantId) {
        pausedTenants.add(tenantId);
        pauseQueues.putIfAbsent(tenantId, new ConcurrentLinkedQueue<>());
        log.info("Tenant {} paused", tenantId);
    }

    public void resume(String tenantId) {
        pausedTenants.remove(tenantId);
        Queue<String> queue = pauseQueues.remove(tenantId);
        if (queue != null && !queue.isEmpty()) {
            log.info("Draining {} buffered messages for tenant {}", queue.size(), tenantId);
            queue.forEach(json -> sendToEvents(tenantId, json));
        }
        log.info("Tenant {} resumed", tenantId);
    }

    public Set<String> getPausedTenants() {
        return Collections.unmodifiableSet(pausedTenants);
    }

    public Map<String, Long> getLagSnapshot() {
        return Collections.unmodifiableMap(lagSnapshot);
    }

    // ---- Poll loop ----

    private void pollLoop() {
        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    routeRecord(record);
                }
                consumer.commitSync();
                updateLag();
            }
        } catch (org.apache.kafka.common.errors.WakeupException e) {
            if (running) log.error("Router poll loop woken up unexpectedly");
        } catch (Exception e) {
            if (running) log.error("Router poll loop error: {}", e.getMessage(), e);
        } finally {
            try { consumer.close(); } catch (Exception ignored) {}
        }
    }

    private void routeRecord(ConsumerRecord<String, String> record) {
        String tenantId = extractTenantId(record.value());
        if (tenantId == null) {
            log.warn("Cannot extract tenantId from record at offset {}, dropping", record.offset());
            dropTotal.increment();
            return;
        }

        if (pausedTenants.contains(tenantId)) {
            Queue<String> queue = pauseQueues.computeIfAbsent(tenantId, k -> new ConcurrentLinkedQueue<>());
            if (queue.size() >= props.pauseQueueMaxSize()) {
                // Back-pressure: pause the Kafka partition so no more messages arrive.
                Set<TopicPartition> assignment = consumer.assignment();
                consumer.pause(assignment);
                log.warn("Pause queue full for tenant {} (size={}), pausing Kafka partitions", tenantId, queue.size());
            } else {
                queue.offer(record.value());
                log.debug("Buffered message for paused tenant {} (queue size={})", tenantId, queue.size());
            }
            return;
        }

        sendToEvents(tenantId, record.value());
    }

    private void sendToEvents(String tenantId, String json) {
        int attempt = 0;
        long backoffMs = props.producerRetryBackoffMs();
        while (attempt < props.producerRetryAttempts()) {
            attempt++;
            try {
                // Using tenantId as the Kafka message key ensures all messages for a tenant
                // land on the same partition (consistent hashing by default partitioner).
                producer.send(new ProducerRecord<>(props.ingestionEventsTopic(), tenantId, json)).get();
                return;
            } catch (Exception e) {
                log.warn("Producer error (attempt {}/{}): {}", attempt, props.producerRetryAttempts(), e.getMessage());
                if (attempt < props.producerRetryAttempts()) {
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    backoffMs = Math.min(backoffMs * 2, 5_000);
                }
            }
        }
        log.error("Dropping message for tenant {} after {} retries", tenantId, props.producerRetryAttempts());
        dropTotal.increment();
    }

    private String extractTenantId(String json) {
        try {
            return mapper.readValue(json, IngestJobRequest.class).tenantId();
        } catch (Exception e) {
            return null;
        }
    }

    private void updateLag() {
        try {
            Map<MetricName, ? extends Metric> metrics = consumer.metrics();
            Map<String, Long> snap = new LinkedHashMap<>();
            for (Map.Entry<MetricName, ? extends Metric> entry : metrics.entrySet()) {
                MetricName name = entry.getKey();
                if ("records-lag".equals(name.name())) {
                    Object val = entry.getValue().metricValue();
                    if (val instanceof Number n) {
                        snap.put(name.tags().getOrDefault("topic-partition", name.tags().toString()),
                                n.longValue());
                    }
                }
            }
            lagSnapshot = snap;
        } catch (Exception e) {
            log.debug("Could not update lag snapshot: {}", e.getMessage());
        }
    }

    public long getDropTotal() {
        return dropTotal.sum();
    }
}
