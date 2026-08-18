package org.synanton.synflux.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.synanton.common.kafka.IngestJobRequest;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.JobRow;
import org.synanton.ingestioncache.domain.OutboxRow;
import org.synanton.synflux.runner.IngestionJobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/ingest")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final IngestionJobRunner runner;
    private final IngestionCacheClient cacheClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired(required = false)
    private KafkaProducer<String, String> kafkaProducer;

    public IngestController(IngestionJobRunner runner, IngestionCacheClient cacheClient) {
        this.runner = runner;
        this.cacheClient = cacheClient;
    }

    /**
     * Phase 3 Kafka-backed path: enqueues the job to {@code ingestion_requests}
     * and returns 202 Accepted immediately. Falls back to inline execution if
     * Kafka is not configured.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> enqueueJob(@RequestBody Map<String, String> body) {
        String tenant = body.getOrDefault("tenant", "demo");
        String source = body.getOrDefault("source", "filesystem");
        String path = body.getOrDefault("path", "/demo-data/documents");
        UUID jobId = UUID.randomUUID();
        String traceId = UUID.randomUUID().toString();

        JobRow queued = new JobRow(tenant, jobId, Instant.now(), null,
                "QUEUED", source, path, 0, 0, null, 0, 0, 0, 0, 0, 0, 0);
        try { cacheClient.upsertJob(queued); } catch (Exception e) {
            log.warn("Could not persist QUEUED job row: {}", e.getMessage());
        }

        if (kafkaProducer != null) {
            IngestJobRequest req = new IngestJobRequest(tenant, jobId, source, path, 5, traceId);
            try {
                String json = mapper.writeValueAsString(req);
                kafkaProducer.send(new ProducerRecord<>("ingestion_requests", tenant, json));
                log.info("Job {} enqueued to ingestion_requests (tenant={})", jobId, tenant);
            } catch (Exception e) {
                log.error("Failed to enqueue job to Kafka, falling back to inline: {}", e.getMessage());
                runner.startJob(tenant, source, path);
            }

            // Write outbox row for observability (tracks the QUEUED transition).
            try {
                OutboxRow outbox = new OutboxRow(tenant, null, null,
                        null, "QUEUED", "ingestion_completed",
                        "{\"jobId\":\"" + jobId + "\",\"state\":\"QUEUED\"}", false, Instant.now());
                cacheClient.insertOutboxRow(outbox);
            } catch (Exception e) {
                log.debug("Could not write outbox row: {}", e.getMessage());
            }

            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "job_id", jobId.toString(),
                    "status", "QUEUED",
                    "estimated_wait_seconds", 15
            ));
        }

        // Fallback: inline execution (Phase 1/2 behaviour).
        UUID inlineJobId = runner.startJob(tenant, source, path);
        return ResponseEntity.ok(Map.of("jobId", inlineJobId.toString(), "state", "RUNNING"));
    }

    /**
     * Legacy inline endpoint kept for manual fallback and Phase 1/2 compatibility.
     */
    @PostMapping("/run")
    public Map<String, String> startJobInline(@RequestBody Map<String, String> body) {
        String tenant = body.getOrDefault("tenant", "demo");
        String source = body.getOrDefault("source", "filesystem");
        String path = body.getOrDefault("path", "/demo-data/documents");
        UUID jobId = runner.startJob(tenant, source, path);
        return Map.of("jobId", jobId.toString(), "state", "RUNNING");
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<JobRow> getJob(@PathVariable UUID id,
                                          @RequestParam(defaultValue = "demo") String tenant) {
        return runner.getJob(tenant, id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs")
    public List<JobRow> listJobs(@RequestParam(defaultValue = "demo") String tenant) {
        return runner.listJobs(tenant);
    }
}
