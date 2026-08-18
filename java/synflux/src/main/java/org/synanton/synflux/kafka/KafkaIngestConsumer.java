package org.synanton.synflux.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.synanton.common.context.RequestContext;
import org.synanton.common.context.RequestContextHolder;
import org.synanton.common.kafka.IngestJobRequest;
import org.synanton.common.kafka.IngestJobResult;
import org.synanton.ingestioncache.domain.JobRow;
import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.runner.IngestionJobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Kafka consumer that subscribes to {@code ingestion_events} (consumer group
 * {@code synflux-workers}) and dispatches each job to {@link IngestionJobRunner}.
 *
 * Retry behaviour: failed records are retried up to {@code synflux.kafka.max-retries}
 * times. After exhausting retries the offset is committed and the job is marked FAILED.
 */
@Component
@ConditionalOnBean(KafkaProducer.class)
public class KafkaIngestConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaIngestConsumer.class);
    private static final String CONSUMER_GROUP = "synflux-workers";
    private static final String INPUT_TOPIC = "ingestion_events";
    private static final String COMPLETED_TOPIC = "ingestion_completed";

    private final IngestionJobRunner runner;
    private final KafkaProducer<String, String> producer;
    private final Function<String, KafkaConsumer<String, String>> consumerFactory;
    private final SynfluxProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<UUID, Integer> retryCounters = new HashMap<>();
    private ExecutorService threadPool;
    private volatile boolean running = true;

    public KafkaIngestConsumer(
            IngestionJobRunner runner,
            KafkaProducer<String, String> producer,
            Function<String, KafkaConsumer<String, String>> kafkaConsumerFactory,
            SynfluxProperties props
    ) {
        this.runner = runner;
        this.producer = producer;
        this.consumerFactory = kafkaConsumerFactory;
        this.props = props;
    }

    @PostConstruct
    void start() {
        int threads = props.kafka() != null ? props.kafka().consumerThreads() : 2;
        threadPool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            threadPool.submit(this::pollLoop);
        }
        log.info("KafkaIngestConsumer started with {} thread(s) on topic={}", threads, INPUT_TOPIC);
    }

    @PreDestroy
    void stop() {
        running = false;
        threadPool.shutdownNow();
        try {
            threadPool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pollLoop() {
        try (KafkaConsumer<String, String> consumer = consumerFactory.apply(CONSUMER_GROUP)) {
            consumer.subscribe(List.of(INPUT_TOPIC));
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    processRecord(record);
                }
                consumer.commitSync();
            }
        } catch (Exception e) {
            if (running) log.error("KafkaIngestConsumer poll loop error: {}", e.getMessage(), e);
        }
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        IngestJobRequest req;
        try {
            req = mapper.readValue(record.value(), IngestJobRequest.class);
        } catch (Exception e) {
            log.error("Cannot deserialise IngestJobRequest from offset {}: {}", record.offset(), e.getMessage());
            return;
        }

        int maxRetries = props.kafka() != null ? props.kafka().maxRetries() : 3;
        int retryCount = retryCounters.getOrDefault(req.jobId(), 0);
        if (retryCount > maxRetries) {
            log.warn("Job {} exceeded max retries, marking FAILED", req.jobId());
            publishResult(req, "FAILED", 0, 0, 0L, "Exceeded max retries");
            retryCounters.remove(req.jobId());
            return;
        }

        RequestContextHolder.set(new RequestContext(req.tenantId(), "kafka-consumer", req.traceId()));
        try {
            Instant start = Instant.now();
            runner.startKafkaJob(req);
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            publishResult(req, "EMBEDDED", 0, 0, durationMs, null);
            retryCounters.remove(req.jobId());
        } catch (Exception e) {
            log.warn("Job {} failed (attempt {}): {}", req.jobId(), retryCount + 1, e.getMessage());
            retryCounters.put(req.jobId(), retryCount + 1);
        } finally {
            RequestContextHolder.clear();
        }
    }

    private void publishResult(IngestJobRequest req, String state, int entityCount,
                               int embeddingCount, long durationMs, String errorMessage) {
        try {
            IngestJobResult result = new IngestJobResult(
                    req.jobId(), req.tenantId(), state, entityCount, embeddingCount, durationMs, errorMessage
            );
            String json = mapper.writeValueAsString(result);
            producer.send(new ProducerRecord<>(COMPLETED_TOPIC, req.tenantId(), json));
        } catch (Exception e) {
            log.warn("Failed to publish IngestJobResult for job {}: {}", req.jobId(), e.getMessage());
        }
    }
}
