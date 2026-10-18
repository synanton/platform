package org.synanton.ingestioncache.client;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.synanton.ingestioncache.codec.LZ4EmbeddingCodec;
import org.synanton.ingestioncache.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class IngestionCacheClient {

    private static final int TENANT_REGISTRY_BUCKETS = 32;
    private static final Logger log = LoggerFactory.getLogger(IngestionCacheClient.class);
    private final CqlSession session;

    // Caffeine look-aside caches
    private final Cache<String, Optional<String>> analysisInputHashCache = Caffeine.newBuilder()
        .maximumSize(10_000).expireAfterWrite(30, TimeUnit.MINUTES).build();
    private final Cache<String, Optional<float[]>> embeddingChunkHashCache = Caffeine.newBuilder()
        .maximumSize(50_000).expireAfterWrite(60, TimeUnit.MINUTES).build();

    public IngestionCacheClient(CqlSession session) {
        this.session = session;
    }

    /** Registers a tenant before creating durable work that recovery workers must enumerate. */
    public void registerTenant(String tenantId, Instant registeredAt) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        Objects.requireNonNull(registeredAt, "registeredAt");
        int bucket = Math.floorMod(tenantId.hashCode(), TENANT_REGISTRY_BUCKETS);
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.tenant_registry (bucket, tenant_id, registered_at) " +
            "VALUES (?,?,?) IF NOT EXISTS",
            bucket, tenantId, registeredAt
        ));
    }

    /** Enumerates registered tenants from bounded partitions, not a hard-coded tenant allowlist. */
    public List<String> listTenantIds() {
        List<String> tenants = new ArrayList<>();
        for (int bucket = 0; bucket < TENANT_REGISTRY_BUCKETS; bucket++) {
            ResultSet result = session.execute(SimpleStatement.newInstance(
                "SELECT tenant_id FROM ingestion_cache.tenant_registry WHERE bucket=?", bucket));
            for (Row row : result) {
                tenants.add(row.getString("tenant_id"));
            }
        }
        return tenants.stream().distinct().sorted().toList();
    }

    // ---- Manifest ----

    public void upsertManifest(ManifestRow row) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.manifest " +
            "(tenant_id, content_ref_id, ingested_at, schema_version, chunk_strategy, chunk_strategy_version, " +
            "state, storage_tier, archive_location, source_uri, source_sha256, size_bytes, mime_type, " +
            "embedding_quality, enrichment_model_id, ingest_usage, content_cache_reference, processing_run_id) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            row.tenantId(), row.contentRefId(), row.ingestedAt(), row.schemaVersion(),
            row.chunkStrategy(), row.chunkStrategyVersion(), row.state(), row.storageTier(),
            row.archiveLocation(), row.sourceUri(), row.sourceSha256(), row.sizeBytes(),
            row.mimeType(), row.embeddingQuality(), row.enrichmentModelId(), row.ingestUsage(),
            row.contentCacheReference(), row.processingRunId()
        ));
    }

    public Optional<ManifestRow> readManifest(String tenantId, UUID contentRefId) {
        var row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.manifest WHERE tenant_id=? AND content_ref_id=?",
            tenantId, contentRefId
        )).one();
        return row == null ? Optional.empty() : Optional.of(rowToManifest(row));
    }

    public List<ManifestRow> listManifest(String tenantId, int limit) {
        var result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.manifest WHERE tenant_id=? LIMIT ? ALLOW FILTERING",
            tenantId, limit
        ));
        List<ManifestRow> rows = new ArrayList<>();
        for (Row r : result) rows.add(rowToManifest(r));
        return rows;
    }

    private ManifestRow rowToManifest(Row r) {
        return new ManifestRow(
            r.getString("tenant_id"), r.getUuid("content_ref_id"),
            r.getInstant("ingested_at"), r.getInt("schema_version"),
            r.getString("chunk_strategy"), r.getInt("chunk_strategy_version"),
            r.getString("state"), r.getString("storage_tier"),
            r.getString("archive_location"), r.getString("source_uri"),
            r.getString("source_sha256"), r.getLong("size_bytes"),
            r.getString("mime_type"), r.getString("embedding_quality"),
            r.getString("enrichment_model_id"),
            stringOrEmpty(r, "ingest_usage"), stringOrEmpty(r, "content_cache_reference"),
            r.getUuid("processing_run_id")
        );
    }

    // ---- Chunks ----

    public void insertChunk(ChunkRow chunk) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.chunks_payload " +
            "(tenant_id, content_ref_id, chunk_ordinal, chunk_text, chunk_sha256, " +
            "page_start, page_end, section_path, chunk_type, heading, " +
            "source_elements, token_count, structured_content, is_partial_section, classification, " +
            "section_id, parent_section_id, heading_level, source_version_id, processing_run_id) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            chunk.tenantId(), chunk.contentRefId(), chunk.chunkOrdinal(), chunk.chunkText(),
            chunk.chunkSha256(), chunk.pageStart(), chunk.pageEnd(), chunk.sectionPath(),
            chunk.chunkType(), chunk.heading(), chunk.sourceElementsJson(), chunk.tokenCount(),
            chunk.structuredContentJson(), chunk.isPartialSection(),
            new java.util.HashSet<>(chunk.classification()),
            chunk.sectionId(), chunk.parentSectionId(), chunk.headingLevel(),
            chunk.sourceVersionId(), chunk.processingRunId()
        ));
    }

    public void insertChunks(List<ChunkRow> chunks) {
        var batch = BatchStatement.newInstance(DefaultBatchType.LOGGED);
        for (ChunkRow c : chunks) {
            batch = batch.add(SimpleStatement.newInstance(
                "INSERT INTO ingestion_cache.chunks_payload " +
                "(tenant_id, content_ref_id, chunk_ordinal, chunk_text, chunk_sha256, " +
                "page_start, page_end, section_path, chunk_type, heading, " +
                "source_elements, token_count, structured_content, is_partial_section, classification, " +
                "section_id, parent_section_id, heading_level, source_version_id, processing_run_id) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                c.tenantId(), c.contentRefId(), c.chunkOrdinal(), c.chunkText(),
                c.chunkSha256(), c.pageStart(), c.pageEnd(), c.sectionPath(),
                c.chunkType(), c.heading(), c.sourceElementsJson(), c.tokenCount(),
                c.structuredContentJson(), c.isPartialSection(),
                new java.util.HashSet<>(c.classification()),
                c.sectionId(), c.parentSectionId(), c.headingLevel(),
                c.sourceVersionId(), c.processingRunId()
            ));
        }
        session.execute(batch);
    }

    public List<ChunkRow> readChunks(String tenantId, UUID contentRefId) {
        var result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.chunks_payload WHERE tenant_id=? AND content_ref_id=?",
            tenantId, contentRefId
        ));
        List<ChunkRow> rows = new ArrayList<>();
        for (Row r : result) {
            rows.add(new ChunkRow(
                r.getString("tenant_id"), r.getUuid("content_ref_id"),
                r.getInt("chunk_ordinal"), r.getString("chunk_text"),
                r.getString("chunk_sha256"),
                intOrDefault(r, "page_start", -1),
                intOrDefault(r, "page_end", -1),
                stringOrEmpty(r, "section_path"),
                stringOrEmpty(r, "chunk_type"),
                stringOrEmpty(r, "heading"),
                stringOrDefault(r, "source_elements", "[]"),
                intOrDefault(r, "token_count", 0),
                stringOrEmpty(r, "structured_content"),
                boolOrDefault(r, "is_partial_section", false),
                setOrDefault(r, "classification", ChunkRow.PUBLIC_ONLY),
                stringOrEmpty(r, "section_id"),
                stringOrEmpty(r, "parent_section_id"),
                intOrDefault(r, "heading_level", 0),
                r.getUuid("source_version_id"),
                r.getUuid("processing_run_id")
            ));
        }
        return rows;
    }

    // ---- Knowledge processing runs (Design 1.25 §12) ----

    public void upsertKnowledgeProcessingRun(KnowledgeProcessingRunRow run) {
        registerTenant(run.tenantId(), run.startedAt());
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.knowledge_processing_runs " +
            "(tenant_id, processing_run_id, producer, producer_version, configuration_json, input_json, " +
            "output_json, status, started_at, completed_at, error_code, error_message) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            run.tenantId(), run.processingRunId(), run.producer(), run.producerVersion(),
            run.configurationJson(), run.inputJson(), run.outputJson(), run.status(),
            run.startedAt(), run.completedAt(), run.errorCode(), run.errorMessage()
        ));
    }

    public Optional<KnowledgeProcessingRunRow> readKnowledgeProcessingRun(String tenantId, UUID processingRunId) {
        Row row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.knowledge_processing_runs WHERE tenant_id=? AND processing_run_id=?",
            tenantId, processingRunId
        )).one();
        return row == null ? Optional.empty() : Optional.of(new KnowledgeProcessingRunRow(
            row.getString("tenant_id"), row.getUuid("processing_run_id"),
            row.getString("producer"), row.getString("producer_version"),
            stringOrEmpty(row, "configuration_json"), stringOrEmpty(row, "input_json"),
            stringOrEmpty(row, "output_json"), row.getString("status"),
            row.getInstant("started_at"), row.getInstant("completed_at"),
            row.getString("error_code"), row.getString("error_message")
        ));
    }

    private static boolean boolOrDefault(Row r, String column, boolean fallback) {
        try {
            if (r.isNull(column)) {
                return fallback;
            }
            return r.getBoolean(column);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String stringOrDefault(Row r, String column, String fallback) {
        try {
            String v = r.getString(column);
            return v == null ? fallback : v;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int intOrDefault(Row r, String column, int fallback) {
        try {
            if (r.isNull(column)) {
                return fallback;
            }
            return r.getInt(column);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String stringOrEmpty(Row r, String column) {
        try {
            String v = r.getString(column);
            return v == null ? "" : v;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static List<String> setOrDefault(Row r, String column, List<String> fallback) {
        try {
            if (r.isNull(column)) {
                return fallback;
            }
            Set<String> values = r.getSet(column, String.class);
            if (values == null || values.isEmpty()) {
                return fallback;
            }
            return List.copyOf(values);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ---- Jobs ----

    public void upsertJob(JobRow job) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.jobs (tenant_id, job_id, started_at, completed_at, state, source, source_path, " +
            "processed_count, error_count, last_error, enriched_count, embedded_count, enrichment_cache_hits, " +
            "embedding_cache_hits, enrichment_errors, embedding_errors, skipped_already_embedded) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            job.tenantId(), job.jobId(), job.startedAt(), job.completedAt(), job.state(),
            job.source(), job.sourcePath(), job.processedCount(), job.errorCount(), job.lastError(),
            job.enrichedCount(), job.embeddedCount(), job.enrichmentCacheHits(), job.embeddingCacheHits(),
            job.enrichmentErrors(), job.embeddingErrors(), job.skippedAlreadyEmbedded()
        ));
    }

    public List<JobRow> listJobs(String tenantId, int limit) {
        var result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.jobs WHERE tenant_id=? LIMIT ?",
            tenantId, limit
        ));
        List<JobRow> rows = new ArrayList<>();
        for (Row r : result) {
            rows.add(new JobRow(
                r.getString("tenant_id"), r.getUuid("job_id"),
                r.getInstant("started_at"), r.getInstant("completed_at"),
                r.getString("state"), r.getString("source"), r.getString("source_path"),
                r.getInt("processed_count"), r.getInt("error_count"), r.getString("last_error"),
                r.getInt("enriched_count"), r.getInt("embedded_count"),
                r.getInt("enrichment_cache_hits"), r.getInt("embedding_cache_hits"),
                r.getInt("enrichment_errors"), r.getInt("embedding_errors"),
                r.getInt("skipped_already_embedded")
            ));
        }
        return rows;
    }

    // ---- Source lifecycle (v1.28) ----

    /** Creates one tenant-scoped source configuration without allowing connector identity to drift. */
    public SourceRow createSourceIfAbsent(SourceRow source) {
        registerTenant(source.tenantId(), source.createdAt());
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.sources " +
            "(tenant_id, source_id, connector_id, connector_type, source_endpoint, capability_flags, status, " +
            "created_at, last_successful_sync_at) VALUES (?,?,?,?,?,?,?,?,?) IF NOT EXISTS",
            source.tenantId(), source.sourceId(), source.connectorId(), source.connectorType(), source.sourceEndpoint(),
            new HashSet<>(source.capabilityFlags()), source.status(), source.createdAt(), source.lastSuccessfulSyncAt()
        ));
        if (result.wasApplied()) {
            return source;
        }
        SourceRow existing = readSource(source.tenantId(), source.sourceId())
            .orElseThrow(() -> new IllegalStateException("Source insert lost without a readable row"));
        if (!existing.connectorId().equals(source.connectorId())
                || !existing.connectorType().equals(source.connectorType())) {
            throw new SourceIdentityConflictException("source_id is already bound to a different connector identity");
        }
        return existing;
    }

    public WorkflowStateRow createWorkflowStateIfAbsent(WorkflowStateRow workflow) {
        registerTenant(workflow.tenantId(), workflow.createdAt());
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.workflow_states " +
            "(tenant_id, workflow_id, workflow_type, workflow_version, operation_id, run_id, source_id, " +
            "resource_id, principal_id, principal_type, request_id, idempotency_key, state, current_step, " +
            "created_at, updated_at, started_at, completed_at, correlation_id, failure_code, failure_message, " +
            "retry_count, next_attempt_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) IF NOT EXISTS",
            workflow.tenantId(), workflow.workflowId(), workflow.workflowType(), workflow.workflowVersion(),
            workflow.operationId(), workflow.runId(), workflow.sourceId(), workflow.resourceId(),
            workflow.principalId(), workflow.principalType(), workflow.requestId(), workflow.idempotencyKey(),
            workflow.state(), workflow.currentStep(), workflow.createdAt(), workflow.updatedAt(),
            workflow.startedAt(), workflow.completedAt(), workflow.correlationId(), workflow.failureCode(),
            workflow.failureMessage(), workflow.retryCount(), workflow.nextAttemptAt()
        ));
        if (result.wasApplied()) {
            return workflow;
        }
        WorkflowStateRow existing = readWorkflowState(workflow.tenantId(), workflow.workflowId())
            .orElseThrow(() -> new IllegalStateException("Workflow insert lost without a readable row"));
        if (!existing.operationId().equals(workflow.operationId())
                || !existing.runId().equals(workflow.runId())
                || !existing.sourceId().equals(workflow.sourceId())
                || !existing.resourceId().equals(workflow.resourceId())) {
            throw new IllegalStateException("workflow_id is already bound to another operation or resource");
        }
        return existing;
    }

    public void upsertWorkflowState(WorkflowStateRow workflow) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.workflow_states " +
            "(tenant_id, workflow_id, workflow_type, workflow_version, operation_id, run_id, source_id, " +
            "resource_id, principal_id, principal_type, request_id, idempotency_key, state, current_step, " +
            "created_at, updated_at, started_at, completed_at, correlation_id, failure_code, failure_message, " +
            "retry_count, next_attempt_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            workflow.tenantId(), workflow.workflowId(), workflow.workflowType(), workflow.workflowVersion(),
            workflow.operationId(), workflow.runId(), workflow.sourceId(), workflow.resourceId(),
            workflow.principalId(), workflow.principalType(), workflow.requestId(), workflow.idempotencyKey(),
            workflow.state(), workflow.currentStep(), workflow.createdAt(), workflow.updatedAt(),
            workflow.startedAt(), workflow.completedAt(), workflow.correlationId(), workflow.failureCode(),
            workflow.failureMessage(), workflow.retryCount(), workflow.nextAttemptAt()
        ));
    }

    public Optional<WorkflowStateRow> readWorkflowState(String tenantId, UUID workflowId) {
        Row row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.workflow_states WHERE tenant_id=? AND workflow_id=?",
            tenantId, workflowId
        )).one();
        return row == null ? Optional.empty() : Optional.of(workflowStateFrom(row));
    }

    public List<WorkflowStateRow> listWorkflowStates(String tenantId) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.workflow_states WHERE tenant_id=?", tenantId));
        List<WorkflowStateRow> workflows = new ArrayList<>();
        for (Row row : result) {
            workflows.add(workflowStateFrom(row));
        }
        return workflows;
    }

    private static WorkflowStateRow workflowStateFrom(Row row) {
        return new WorkflowStateRow(
            row.getString("tenant_id"), row.getUuid("workflow_id"), row.getString("workflow_type"),
            row.getInt("workflow_version"), row.getUuid("operation_id"), row.getUuid("run_id"),
            row.getString("source_id"), row.getString("resource_id"), row.getString("principal_id"),
            row.getString("principal_type"), row.getString("request_id"), row.getString("idempotency_key"),
            row.getString("state"), row.getString("current_step"), row.getInstant("created_at"),
            row.getInstant("updated_at"), row.getInstant("started_at"), row.getInstant("completed_at"),
            row.getString("correlation_id"), row.getString("failure_code"), row.getString("failure_message"),
            intOrDefault(row, "retry_count", 0), row.getInstant("next_attempt_at")
        );
    }

    public void upsertWorkflowStep(WorkflowStepRow step) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.workflow_steps " +
            "(tenant_id, workflow_id, step_id, command_id, command_type, expected_events_json, timeout_millis, " +
            "retry_policy_json, compensation, status, attempt_count, input_json, output_json, started_at, " +
            "completed_at, next_attempt_at, last_error) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            step.tenantId(), step.workflowId(), step.stepId(), step.commandId(), step.commandType(),
            step.expectedEventsJson(), step.timeoutMillis(), step.retryPolicyJson(), step.compensation(),
            step.status(), step.attemptCount(), step.inputJson(), step.outputJson(), step.startedAt(),
            step.completedAt(), step.nextAttemptAt(), step.lastError()
        ));
    }

    public Optional<WorkflowStepRow> readWorkflowStep(String tenantId, UUID workflowId, String stepId) {
        Row row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.workflow_steps WHERE tenant_id=? AND workflow_id=? AND step_id=?",
            tenantId, workflowId, stepId
        )).one();
        return row == null ? Optional.empty() : Optional.of(workflowStepFrom(row));
    }

    private static WorkflowStepRow workflowStepFrom(Row row) {
        return new WorkflowStepRow(
            row.getString("tenant_id"), row.getUuid("workflow_id"), row.getString("step_id"),
            row.getUuid("command_id"), row.getString("command_type"), row.getString("expected_events_json"),
            row.getLong("timeout_millis"), row.getString("retry_policy_json"), row.getString("compensation"),
            row.getString("status"), intOrDefault(row, "attempt_count", 0), row.getString("input_json"),
            row.getString("output_json"), row.getInstant("started_at"), row.getInstant("completed_at"),
            row.getInstant("next_attempt_at"), row.getString("last_error")
        );
    }

    /** Claims one workflow until its lease expires; concurrent Synflux instances converge on one worker. */
    public boolean claimWorkflowLease(
            String tenantId, UUID workflowId, UUID leaseOwner, Instant now, Instant leaseExpiresAt) {
        ResultSet inserted = session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.workflow_leases " +
            "(tenant_id, workflow_id, lease_owner, lease_expires_at) VALUES (?,?,?,?) IF NOT EXISTS",
            tenantId, workflowId, leaseOwner, leaseExpiresAt
        ));
        if (inserted.wasApplied()) {
            return true;
        }
        Row existing = session.execute(SimpleStatement.newInstance(
            "SELECT lease_owner, lease_expires_at FROM ingestion_cache.workflow_leases " +
            "WHERE tenant_id=? AND workflow_id=?",
            tenantId, workflowId
        )).one();
        if (existing == null) {
            return false;
        }
        Instant expiresAt = existing.getInstant("lease_expires_at");
        UUID existingOwner = existing.getUuid("lease_owner");
        if (expiresAt != null && expiresAt.isAfter(now)) {
            return false;
        }
        ResultSet reclaimed = session.execute(SimpleStatement.newInstance(
            "UPDATE ingestion_cache.workflow_leases SET lease_owner=?, lease_expires_at=? " +
            "WHERE tenant_id=? AND workflow_id=? IF lease_owner=?",
            leaseOwner, leaseExpiresAt, tenantId, workflowId, existingOwner
        ));
        return reclaimed.wasApplied();
    }

    public boolean renewWorkflowLease(
            String tenantId, UUID workflowId, UUID leaseOwner, Instant leaseExpiresAt) {
        ResultSet renewed = session.execute(SimpleStatement.newInstance(
            "UPDATE ingestion_cache.workflow_leases SET lease_expires_at=? " +
            "WHERE tenant_id=? AND workflow_id=? IF lease_owner=?",
            leaseExpiresAt, tenantId, workflowId, leaseOwner
        ));
        return renewed.wasApplied();
    }

    public void releaseWorkflowLease(String tenantId, UUID workflowId, UUID leaseOwner) {
        session.execute(SimpleStatement.newInstance(
            "DELETE FROM ingestion_cache.workflow_leases WHERE tenant_id=? AND workflow_id=? IF lease_owner=?",
            tenantId, workflowId, leaseOwner
        ));
    }

    public Optional<SourceRow> readSource(String tenantId, String sourceId) {
        Row row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.sources WHERE tenant_id=? AND source_id=?",
            tenantId, sourceId
        )).one();
        return row == null ? Optional.empty() : Optional.of(new SourceRow(
            row.getString("tenant_id"), row.getString("source_id"), row.getString("connector_id"),
            row.getString("connector_type"),
            stringOrEmpty(row, "source_endpoint"), new HashSet<>(setOrDefault(row, "capability_flags", List.of())),
            row.getString("status"), row.getInstant("created_at"), row.getInstant("last_successful_sync_at")
        ));
    }

    public List<SourceRow> listSources(String tenantId) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.sources WHERE tenant_id=?",
            tenantId
        ));
        List<SourceRow> sources = new ArrayList<>();
        for (Row row : result) {
            sources.add(new SourceRow(
                row.getString("tenant_id"), row.getString("source_id"), row.getString("connector_id"),
                row.getString("connector_type"), stringOrEmpty(row, "source_endpoint"),
                new HashSet<>(setOrDefault(row, "capability_flags", List.of())), row.getString("status"),
                row.getInstant("created_at"), row.getInstant("last_successful_sync_at")
            ));
        }
        return sources;
    }

    public void updateSourceConfiguration(
            String tenantId, String sourceId, String sourceEndpoint, Set<String> capabilityFlags) {
        session.execute(SimpleStatement.newInstance(
            "UPDATE ingestion_cache.sources SET source_endpoint=?, capability_flags=? " +
            "WHERE tenant_id=? AND source_id=?",
            sourceEndpoint, new HashSet<>(capabilityFlags), tenantId, sourceId
        ));
    }

    public void markSourceDeleted(String tenantId, String sourceId) {
        session.execute(SimpleStatement.newInstance(
            "UPDATE ingestion_cache.sources SET status='DELETED' WHERE tenant_id=? AND source_id=?",
            tenantId, sourceId
        ));
    }

    /** Adds a discovered resource identity to an existing configured source. */
    public SourceIdentityRow createSourceIdentityIfAbsent(SourceIdentityRow identity) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.source_identities " +
            "(tenant_id, source_id, connector_id, resource_id) VALUES (?,?,?,?) IF NOT EXISTS",
            identity.tenantId(), identity.sourceId(), identity.connectorId(), identity.resourceId()
        ));
        if (result.wasApplied()) {
            return identity;
        }
        return readSourceIdentity(identity.tenantId(), identity.connectorId(), identity.sourceId(), identity.resourceId())
            .orElseThrow(() -> new IllegalStateException("SourceIdentity insert lost without a readable row"));
    }

    public Optional<SourceIdentityRow> readSourceIdentity(
            String tenantId, String connectorId, String sourceId, String resourceId) {
        Row row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.source_identities WHERE tenant_id=? AND source_id=? " +
            "AND connector_id=? AND resource_id=?",
            tenantId, sourceId, connectorId, resourceId
        )).one();
        return row == null ? Optional.empty() : Optional.of(new SourceIdentityRow(
            row.getString("tenant_id"), row.getString("connector_id"), row.getString("source_id"),
            row.getString("resource_id")
        ));
    }

    public List<SourceIdentityRow> listSourceIdentities(
            String tenantId, String sourceId, String connectorId) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.source_identities WHERE tenant_id=? AND source_id=? " +
            "AND connector_id=?",
            tenantId, sourceId, connectorId
        ));
        List<SourceIdentityRow> identities = new ArrayList<>();
        for (Row row : result) {
            identities.add(new SourceIdentityRow(
                row.getString("tenant_id"), row.getString("connector_id"), row.getString("source_id"),
                row.getString("resource_id")
            ));
        }
        return identities;
    }

    public void markSourceSyncSucceeded(String tenantId, String sourceId, Instant syncedAt) {
        session.execute(SimpleStatement.newInstance(
            "UPDATE ingestion_cache.sources SET last_successful_sync_at=? WHERE tenant_id=? AND source_id=?",
            syncedAt, tenantId, sourceId
        ));
    }

    /** SourceVersion and its SourcePublished event share one immutable Cassandra row. */
    public boolean insertSourceVersionAndOutboxIfAbsent(SourceVersionRow version, SourceOutboxRow event) {
        if (!version.tenantId().equals(event.tenantId())
                || !version.connectorId().equals(event.connectorId())
                || !version.sourceId().equals(event.sourceId())
                || !version.resourceId().equals(event.resourceId())
                || !version.versionId().equals(event.sourceVersionId())) {
            throw new IllegalArgumentException("SourceVersion and outbox event identities must match");
        }
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.source_versions " +
            "(tenant_id, connector_id, source_id, resource_id, version_id, content_digest, source_revision, " +
            "observed_at, content_ref_id, incarnation_id, content_cache_reference, event_id, event_type, " +
            "event_payload_json, event_published, event_published_at, event_attempt_count, event_last_error, " +
            "event_next_attempt_at, event_dead_lettered) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,false,?,?,?,?,?) IF NOT EXISTS",
            version.tenantId(), version.connectorId(), version.sourceId(), version.resourceId(), version.versionId(),
            version.contentDigest(), version.sourceRevision(), version.observedAt(), version.contentRefId(),
            version.incarnationId(), version.contentCacheReference(), event.eventId(), event.eventType(), event.payloadJson(),
            event.publishedAt(), event.attemptCount(), event.lastError(), event.nextAttemptAt(), event.deadLettered()
        ));
        return result.wasApplied();
    }

    public List<SourceOutboxRow> listSourceOutbox(
            String tenantId, String connectorId, String sourceId, String resourceId) {
        ResultSet versions = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.source_versions WHERE tenant_id=? AND connector_id=? " +
            "AND source_id=? AND resource_id=?",
            tenantId, connectorId, sourceId, resourceId
        ));
        List<SourceOutboxRow> rows = new ArrayList<>();
        for (Row row : versions) {
            UUID eventId = row.getUuid("event_id");
            if (eventId != null) {
                rows.add(new SourceOutboxRow(
                    row.getString("tenant_id"), row.getString("connector_id"), row.getString("source_id"),
                    row.getString("resource_id"), eventId, row.getString("event_type"), row.getUuid("version_id"),
                    null, row.getString("event_payload_json"), row.getInstant("observed_at"),
                    row.getBoolean("event_published"), row.getInstant("event_published_at"),
                    intOrDefault(row, "event_attempt_count", 0), row.getString("event_last_error"),
                    row.getInstant("event_next_attempt_at"), boolOrDefault(row, "event_dead_lettered", false)
                ));
            }
        }
        ResultSet tombstones = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.source_tombstones WHERE tenant_id=? AND connector_id=? " +
            "AND source_id=? AND resource_id=?",
            tenantId, connectorId, sourceId, resourceId
        ));
        for (Row row : tombstones) {
            UUID eventId = row.getUuid("event_id");
            if (eventId != null) {
                rows.add(new SourceOutboxRow(
                    row.getString("tenant_id"), row.getString("connector_id"), row.getString("source_id"),
                    row.getString("resource_id"), eventId, row.getString("event_type"), null,
                    row.getUuid("tombstone_id"), row.getString("event_payload_json"), row.getInstant("deleted_at"),
                    row.getBoolean("event_published"), row.getInstant("event_published_at"),
                    intOrDefault(row, "event_attempt_count", 0), row.getString("event_last_error"),
                    row.getInstant("event_next_attempt_at"), boolOrDefault(row, "event_dead_lettered", false)
                ));
            }
        }
        return rows.stream().sorted(Comparator.comparing(SourceOutboxRow::createdAt)
            .thenComparing(SourceOutboxRow::eventId)).toList();
    }

    public List<SourceOutboxRow> listUnpublishedSourceOutbox(String tenantId, int limit, Instant now) {
        List<SourceOutboxRow> pending = new ArrayList<>();
        for (SourceRow source : listSources(tenantId)) {
            for (SourceIdentityRow identity : listSourceIdentities(
                    tenantId, source.sourceId(), source.connectorId())) {
                boolean orderingBlocked = false;
                for (SourceOutboxRow event : listSourceOutbox(
                        tenantId, source.connectorId(), source.sourceId(), identity.resourceId())) {
                    if (event.published()) {
                        continue;
                    }
                    if (event.deadLettered() || (event.nextAttemptAt() != null && event.nextAttemptAt().isAfter(now))) {
                        orderingBlocked = true;
                        continue;
                    }
                    if (!orderingBlocked) {
                        pending.add(event);
                    }
                }
            }
        }
        return pending.stream().sorted(Comparator.comparing(SourceOutboxRow::createdAt))
            .limit(limit).toList();
    }

    public void markSourceOutboxPublished(SourceOutboxRow event, Instant publishedAt) {
        if (event.sourceVersionId() != null) {
            session.execute(SimpleStatement.newInstance(
                "UPDATE ingestion_cache.source_versions SET event_published=true, event_published_at=?, " +
                "event_last_error=null, event_next_attempt_at=null WHERE tenant_id=? " +
                "AND connector_id=? AND source_id=? AND resource_id=? AND version_id=?",
                publishedAt, event.tenantId(), event.connectorId(), event.sourceId(), event.resourceId(),
                event.sourceVersionId()
            ));
        } else {
            session.execute(SimpleStatement.newInstance(
                "UPDATE ingestion_cache.source_tombstones SET event_published=true, event_published_at=?, " +
                "event_last_error=null, event_next_attempt_at=null WHERE tenant_id=? " +
                "AND connector_id=? AND source_id=? AND resource_id=? AND tombstone_id=?",
                publishedAt, event.tenantId(), event.connectorId(), event.sourceId(), event.resourceId(),
                event.tombstoneId()
            ));
        }
    }

    public void recordSourceOutboxFailure(
            SourceOutboxRow event,
            int attemptCount,
            String error,
            Instant nextAttemptAt,
            boolean deadLettered,
            Instant occurredAt) {
        if (deadLettered) {
            session.execute(SimpleStatement.newInstance(
                "INSERT INTO ingestion_cache.dead_letter_events " +
                "(tenant_id, event_id, event_type, aggregate_type, aggregate_id, payload_json, created_at, " +
                "dead_lettered_at, attempt_count, last_error) VALUES (?,?,?,?,?,?,?,?,?,?)",
                event.tenantId(), event.eventId(), event.eventType(), "SourceIdentity",
                event.connectorId() + ":" + event.sourceId() + ":" + event.resourceId(), event.payloadJson(),
                event.createdAt(), occurredAt, attemptCount, error
            ));
        }
        if (event.sourceVersionId() != null) {
            session.execute(SimpleStatement.newInstance(
                "UPDATE ingestion_cache.source_versions SET event_attempt_count=?, event_last_error=?, " +
                "event_next_attempt_at=?, event_dead_lettered=? WHERE tenant_id=? AND connector_id=? " +
                "AND source_id=? AND resource_id=? AND version_id=?",
                attemptCount, error, nextAttemptAt, deadLettered, event.tenantId(), event.connectorId(),
                event.sourceId(), event.resourceId(), event.sourceVersionId()
            ));
        } else {
            session.execute(SimpleStatement.newInstance(
                "UPDATE ingestion_cache.source_tombstones SET event_attempt_count=?, event_last_error=?, " +
                "event_next_attempt_at=?, event_dead_lettered=? WHERE tenant_id=? AND connector_id=? " +
                "AND source_id=? AND resource_id=? AND tombstone_id=?",
                attemptCount, error, nextAttemptAt, deadLettered, event.tenantId(), event.connectorId(),
                event.sourceId(), event.resourceId(), event.tombstoneId()
            ));
        }
    }

    /** Requeues the immutable dead-lettered event without changing its source fact or event ID. */
    public boolean replaySourceOutbox(SourceOutboxRow event) {
        ResultSet result;
        if (event.sourceVersionId() != null) {
            result = session.execute(SimpleStatement.newInstance(
                "UPDATE ingestion_cache.source_versions SET event_published=false, event_published_at=null, " +
                "event_attempt_count=0, event_last_error=null, event_next_attempt_at=null, " +
                "event_dead_lettered=false WHERE tenant_id=? AND connector_id=? AND source_id=? " +
                "AND resource_id=? AND version_id=? IF event_dead_lettered=true",
                event.tenantId(), event.connectorId(), event.sourceId(), event.resourceId(), event.sourceVersionId()
            ));
        } else {
            result = session.execute(SimpleStatement.newInstance(
                "UPDATE ingestion_cache.source_tombstones SET event_published=false, event_published_at=null, " +
                "event_attempt_count=0, event_last_error=null, event_next_attempt_at=null, " +
                "event_dead_lettered=false WHERE tenant_id=? AND connector_id=? AND source_id=? " +
                "AND resource_id=? AND tombstone_id=? IF event_dead_lettered=true",
                event.tenantId(), event.connectorId(), event.sourceId(), event.resourceId(), event.tombstoneId()
            ));
        }
        if (result.wasApplied()) {
            session.execute(SimpleStatement.newInstance(
                "DELETE FROM ingestion_cache.dead_letter_events WHERE tenant_id=? AND event_id=?",
                event.tenantId(), event.eventId()
            ));
        }
        return result.wasApplied();
    }

    public Optional<SourceVersionRow> readSourceVersion(
            String tenantId, String connectorId, String sourceId, String resourceId, UUID versionId) {
        Row row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.source_versions WHERE tenant_id=? AND connector_id=? " +
            "AND source_id=? AND resource_id=? AND version_id=?",
            tenantId, connectorId, sourceId, resourceId, versionId
        )).one();
        return row == null ? Optional.empty() : Optional.of(sourceVersionFrom(row));
    }

    public List<SourceVersionRow> listSourceVersions(
            String tenantId, String connectorId, String sourceId, String resourceId) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.source_versions WHERE tenant_id=? AND connector_id=? " +
            "AND source_id=? AND resource_id=?",
            tenantId, connectorId, sourceId, resourceId
        ));
        List<SourceVersionRow> rows = new ArrayList<>();
        for (Row row : result) {
            rows.add(sourceVersionFrom(row));
        }
        return rows;
    }

    private static SourceVersionRow sourceVersionFrom(Row row) {
        return new SourceVersionRow(
            row.getString("tenant_id"), row.getString("connector_id"), row.getString("source_id"),
            row.getString("resource_id"), row.getUuid("version_id"), row.getString("content_digest"),
            row.getString("source_revision"), row.getInstant("observed_at"), row.getUuid("content_ref_id"),
            row.getUuid("incarnation_id"), stringOrEmpty(row, "content_cache_reference")
        );
    }

    public boolean insertSourceTombstoneIfAbsent(SourceTombstoneRow tombstone, SourceOutboxRow event) {
        if (!tombstone.tenantId().equals(event.tenantId())
                || !tombstone.connectorId().equals(event.connectorId())
                || !tombstone.sourceId().equals(event.sourceId())
                || !tombstone.resourceId().equals(event.resourceId())
                || !tombstone.tombstoneId().equals(event.tombstoneId())) {
            throw new IllegalArgumentException("Tombstone and outbox event identities must match");
        }
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.source_tombstones " +
            "(tenant_id, connector_id, source_id, resource_id, deleted_at, tombstone_id, " +
            "deleted_by_principal_id, deleted_by_principal_type, version_count, versions_manifest_ref, " +
            "next_incarnation_id, event_id, event_type, event_payload_json, event_published, event_published_at, " +
            "event_attempt_count, event_last_error, event_next_attempt_at, event_dead_lettered) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,false,?,?,?,?,?) IF NOT EXISTS",
            tombstone.tenantId(), tombstone.connectorId(), tombstone.sourceId(), tombstone.resourceId(),
            tombstone.deletedAt(), tombstone.tombstoneId(), tombstone.deletedByPrincipalId(),
            tombstone.deletedByPrincipalType(), tombstone.versionCount(), tombstone.versionsManifestRef(),
            tombstone.nextIncarnationId(), event.eventId(), event.eventType(), event.payloadJson(),
            event.publishedAt(), event.attemptCount(), event.lastError(), event.nextAttemptAt(), event.deadLettered()
        ));
        return result.wasApplied();
    }

    public Optional<SourceTombstoneRow> readLatestSourceTombstone(
            String tenantId, String connectorId, String sourceId, String resourceId) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.source_tombstones WHERE tenant_id=? AND connector_id=? " +
            "AND source_id=? AND resource_id=?",
            tenantId, connectorId, sourceId, resourceId
        ));
        return result.all().stream()
            .map(row -> new SourceTombstoneRow(
                row.getString("tenant_id"), row.getString("connector_id"), row.getString("source_id"),
                row.getString("resource_id"), row.getUuid("tombstone_id"), row.getInstant("deleted_at"),
                row.getString("deleted_by_principal_id"), row.getString("deleted_by_principal_type"),
                row.getInt("version_count"), row.getUuid("versions_manifest_ref"), row.getUuid("next_incarnation_id")
            ))
            .max(Comparator.comparing(SourceTombstoneRow::deletedAt));
    }

    public void insertVersionManifestEntries(String tenantId, UUID manifestRef, List<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return;
        }
        BatchStatement batch = BatchStatement.newInstance(DefaultBatchType.LOGGED);
        for (UUID versionId : versionIds) {
            batch = batch.add(SimpleStatement.newInstance(
                "INSERT INTO ingestion_cache.source_version_manifest_entries " +
                "(tenant_id, manifest_ref, version_id) VALUES (?,?,?)",
                tenantId, manifestRef, versionId
            ));
        }
        session.execute(batch);
    }

    public List<UUID> readVersionManifest(String tenantId, UUID manifestRef) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "SELECT version_id FROM ingestion_cache.source_version_manifest_entries " +
            "WHERE tenant_id=? AND manifest_ref=?",
            tenantId, manifestRef
        ));
        List<UUID> versionIds = new ArrayList<>();
        for (Row row : result) {
            versionIds.add(row.getUuid("version_id"));
        }
        return versionIds;
    }

    public void upsertIngestionRun(IngestionRunRow run) {
        SyncResultRow result = run.result();
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.ingestion_runs " +
            "(tenant_id, run_id, operation_id, source_id, status, started_at, completed_at, discovered, changed, " +
            "unchanged, deleted, failed, skipped, quarantined, cancelled, error_ref) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            run.tenantId(), run.runId(), run.operationId(), run.sourceId(), run.status(), run.startedAt(),
            run.completedAt(), result == null ? null : result.discovered(),
            result == null ? null : result.changed(), result == null ? null : result.unchanged(),
            result == null ? null : result.deleted(), result == null ? null : result.failed(),
            result == null ? null : result.skipped(), result == null ? null : result.quarantined(),
            result == null ? null : result.cancelled(), run.errorRef()
        ));
    }

    public void insertIngestionManifestIfAbsent(IngestionManifestRow manifest) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.ingestion_manifests " +
            "(tenant_id, run_id, manifest_id, generated_at, entry_count, entries_ref) " +
            "VALUES (?,?,?,?,?,?) IF NOT EXISTS",
            manifest.tenantId(), manifest.runId(), manifest.manifestId(), manifest.generatedAt(),
            manifest.entryCount(), manifest.entriesRef()
        ));
        if (!result.wasApplied()) {
            IngestionManifestRow existing = readIngestionManifest(manifest.tenantId(), manifest.runId())
                .orElseThrow(() -> new IllegalStateException("Manifest insert lost without a readable row"));
            if (!existing.manifestId().equals(manifest.manifestId())
                    || existing.entryCount() != manifest.entryCount()
                    || !existing.entriesRef().equals(manifest.entriesRef())) {
                throw new IllegalStateException("Completed ingestion manifest is immutable");
            }
        }
    }

    public Optional<IngestionManifestRow> readIngestionManifest(String tenantId, UUID runId) {
        Row row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.ingestion_manifests WHERE tenant_id=? AND run_id=?",
            tenantId, runId
        )).one();
        return row == null ? Optional.empty() : Optional.of(new IngestionManifestRow(
            row.getString("tenant_id"), row.getUuid("run_id"), row.getUuid("manifest_id"),
            row.getInstant("generated_at"), row.getInt("entry_count"), row.getString("entries_ref")
        ));
    }

    public void insertIngestionError(IngestionErrorRow error) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.ingestion_errors " +
            "(tenant_id, error_id, type, title, status, detail, instance, code, retryable, request_id, " +
            "details_json, occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            error.tenantId(), error.errorId(), error.type(), error.title(), error.status(), error.detail(),
            error.instance(), error.code(), error.retryable(), error.requestId(), error.detailsJson(), error.occurredAt()
        ));
    }

    public Optional<IngestionErrorRow> readIngestionError(String tenantId, UUID errorId) {
        Row row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.ingestion_errors WHERE tenant_id=? AND error_id=?",
            tenantId, errorId
        )).one();
        return row == null ? Optional.empty() : Optional.of(new IngestionErrorRow(
            row.getString("tenant_id"), row.getUuid("error_id"), row.getString("type"),
            row.getString("title"), row.getInt("status"), row.getString("detail"), row.getString("instance"),
            row.getString("code"), row.getBoolean("retryable"), row.getString("request_id"),
            row.getString("details_json"), row.getInstant("occurred_at")
        ));
    }

    public Optional<IngestionRunRow> readIngestionRun(String tenantId, UUID runId) {
        Row row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.ingestion_runs WHERE tenant_id=? AND run_id=?",
            tenantId, runId
        )).one();
        if (row == null) {
            return Optional.empty();
        }
        Instant completedAt = row.getInstant("completed_at");
        SyncResultRow result = completedAt == null ? null : new SyncResultRow(
            row.getInt("discovered"), row.getInt("changed"), row.getInt("unchanged"), row.getInt("deleted"),
            row.getInt("failed"), row.getInt("skipped"), row.getInt("quarantined"), row.getInt("cancelled")
        );
        return Optional.of(new IngestionRunRow(
            row.getString("tenant_id"), row.getUuid("run_id"), row.getUuid("operation_id"),
            row.getString("source_id"), row.getString("status"), row.getInstant("started_at"),
            completedAt, result, row.getString("error_ref")
        ));
    }

    public void upsertManifestEntry(IngestionManifestEntryRow entry) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.ingestion_manifest_entries " +
            "(tenant_id, run_id, connector_id, source_id, resource_id, result, source_version_id, error_ref, " +
            "observed_at) VALUES (?,?,?,?,?,?,?,?,?)",
            entry.tenantId(), entry.runId(), entry.connectorId(), entry.sourceId(), entry.resourceId(),
            entry.result(), entry.sourceVersionId(), entry.errorRef(), entry.observedAt()
        ));
    }

    public List<IngestionManifestEntryRow> listManifestEntries(String tenantId, UUID runId) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.ingestion_manifest_entries WHERE tenant_id=? AND run_id=?",
            tenantId, runId
        ));
        List<IngestionManifestEntryRow> entries = new ArrayList<>();
        for (Row row : result) {
            entries.add(new IngestionManifestEntryRow(
                row.getString("tenant_id"), row.getUuid("run_id"), row.getString("connector_id"),
                row.getString("source_id"), row.getString("resource_id"), row.getString("result"),
                row.getUuid("source_version_id"), row.getString("error_ref"), row.getInstant("observed_at")
            ));
        }
        return entries;
    }

    public boolean createOperationIfAbsent(OperationRow operation) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.operations " +
            "(tenant_id, operation_id, operation_type, status, requested_by_principal_id, created_at, started_at, " +
            "completed_at, progress_completed, progress_total, outcome, error_id, request_id, idempotency_key) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) IF NOT EXISTS",
            operation.tenantId(), operation.operationId(), operation.type(), operation.status(),
            operation.requestedByPrincipalId(), operation.createdAt(), operation.startedAt(), operation.completedAt(),
            operation.progressCompleted(), operation.progressTotal(), operation.outcome(), operation.errorId(),
            operation.requestId(), operation.idempotencyKey()
        ));
        return result.wasApplied();
    }

    public void upsertOperation(OperationRow operation) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.operations " +
            "(tenant_id, operation_id, operation_type, status, requested_by_principal_id, created_at, started_at, " +
            "completed_at, progress_completed, progress_total, outcome, error_id, request_id, idempotency_key) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            operation.tenantId(), operation.operationId(), operation.type(), operation.status(),
            operation.requestedByPrincipalId(), operation.createdAt(), operation.startedAt(), operation.completedAt(),
            operation.progressCompleted(), operation.progressTotal(), operation.outcome(), operation.errorId(),
            operation.requestId(), operation.idempotencyKey()
        ));
    }

    public Optional<OperationRow> readOperation(String tenantId, UUID operationId) {
        Row row = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.operations WHERE tenant_id=? AND operation_id=?",
            tenantId, operationId
        )).one();
        return row == null ? Optional.empty() : Optional.of(new OperationRow(
            row.getString("tenant_id"), row.getUuid("operation_id"), row.getString("operation_type"),
            row.getString("status"), row.getString("requested_by_principal_id"), row.getInstant("created_at"),
            row.getInstant("started_at"), row.getInstant("completed_at"), row.getInt("progress_completed"),
            row.getInt("progress_total"), row.getString("outcome"), row.getUuid("error_id"),
            row.getString("request_id"), row.getString("idempotency_key")
        ));
    }

    /** Reserves the PAPI-3 tenant/principal/endpoint/idempotency-key command identity. */
    public UUID reserveOperationIdempotency(
            String tenantId,
            String principalId,
            String endpoint,
            String idempotencyKey,
            UUID candidateOperationId,
            Instant reservedAt) {
        ResultSet result = session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.operation_idempotency " +
            "(tenant_id, principal_id, endpoint, idempotency_key, operation_id, created_at) " +
            "VALUES (?,?,?,?,?,?) IF NOT EXISTS",
            tenantId, principalId, endpoint, idempotencyKey, candidateOperationId, reservedAt
        ));
        if (result.wasApplied()) {
            return candidateOperationId;
        }
        Row existing = session.execute(SimpleStatement.newInstance(
            "SELECT operation_id FROM ingestion_cache.operation_idempotency WHERE tenant_id=? " +
            "AND principal_id=? AND endpoint=? AND idempotency_key=?",
            tenantId, principalId, endpoint, idempotencyKey
        )).one();
        if (existing == null) {
            throw new IllegalStateException("Idempotency reservation lost without a readable row");
        }
        return existing.getUuid("operation_id");
    }

    // ---- Analysis Cache (Phase 2) ----

    public void upsertAnalysis(AnalysisRow row) {
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.analysis_cache (tenant_id, content_ref_id, chunk_ordinal, pass_number, " +
            "model_id, prompt_version, analysis_json, input_sha256, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            row.tenantId(), row.contentRefId(), row.chunkOrdinal(), row.passNumber(),
            row.modelId(), row.promptVersion(), row.analysisJson(), row.inputSha256(), row.createdAt()
        ));
    }

    public Optional<AnalysisRow> readAnalysis(String tenantId, UUID contentRefId, int chunkOrdinal, int passNumber) {
        var r = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.analysis_cache WHERE tenant_id=? AND content_ref_id=? AND chunk_ordinal=? AND pass_number=?",
            tenantId, contentRefId, chunkOrdinal, passNumber
        )).one();
        return r == null ? Optional.empty() : Optional.of(rowToAnalysis(r));
    }

    public Optional<AnalysisRow> readAnalysisByInputHash(String tenantId, String inputSha256) {
        String cacheKey = tenantId + ":" + inputSha256;
        var cached = analysisInputHashCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.map(json -> new AnalysisRow(tenantId, null, 0, 0, null, null, json, inputSha256, null));
        }
        // Secondary index not available in Cassandra without ALLOW FILTERING or secondary index.
        // For simplicity in Phase 1, iterate (small corpus). Phase 2 can add a secondary index.
        var result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.analysis_cache WHERE tenant_id=? AND input_sha256=? ALLOW FILTERING",
            tenantId, inputSha256
        )).one();
        if (result == null) {
            analysisInputHashCache.put(cacheKey, Optional.empty());
            return Optional.empty();
        }
        var row = rowToAnalysis(result);
        analysisInputHashCache.put(cacheKey, Optional.of(row.analysisJson()));
        return Optional.of(row);
    }

    public List<AnalysisRow> listAnalysis(String tenantId, UUID contentRefId) {
        var rs = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.analysis_cache WHERE tenant_id=? AND content_ref_id=?",
            tenantId, contentRefId
        ));
        List<AnalysisRow> rows = new ArrayList<>();
        for (Row r : rs) rows.add(rowToAnalysis(r));
        return rows;
    }

    private AnalysisRow rowToAnalysis(Row r) {
        return new AnalysisRow(
            r.getString("tenant_id"), r.getUuid("content_ref_id"),
            r.getInt("chunk_ordinal"), r.getInt("pass_number"),
            r.getString("model_id"), r.getString("prompt_version"),
            r.getString("analysis_json"), r.getString("input_sha256"),
            r.getInstant("created_at")
        );
    }

    // ---- Embedding Cache (Phase 2) ----

    public void upsertEmbedding(EmbeddingRow row) {
        byte[] compressed = LZ4EmbeddingCodec.compress(row.embedding());
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.embedding_content_cache (tenant_id, content_ref_id, chunk_ordinal, " +
            "model_id, chunk_sha256, embedding, embedding_dim, created_at) VALUES (?,?,?,?,?,?,?,?)",
            row.tenantId(), row.contentRefId(), row.chunkOrdinal(), row.modelId(),
            row.chunkSha256(), ByteBuffer.wrap(compressed), row.embeddingDim(), row.createdAt()
        ));
    }

    public Optional<EmbeddingRow> readEmbedding(String tenantId, UUID contentRefId, int chunkOrdinal, String modelId) {
        var r = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.embedding_content_cache WHERE tenant_id=? AND content_ref_id=? AND chunk_ordinal=? AND model_id=?",
            tenantId, contentRefId, chunkOrdinal, modelId
        )).one();
        return r == null ? Optional.empty() : Optional.of(rowToEmbedding(r));
    }

    public Optional<EmbeddingRow> readEmbeddingByChunkHash(String tenantId, String chunkSha256, String modelId) {
        String cacheKey = tenantId + ":" + chunkSha256 + ":" + modelId;
        var cached = embeddingChunkHashCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.map(emb -> new EmbeddingRow(tenantId, null, 0, modelId, chunkSha256, emb, emb.length, null));
        }
        var r = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.embedding_content_cache WHERE tenant_id=? AND chunk_sha256=? AND model_id=? ALLOW FILTERING",
            tenantId, chunkSha256, modelId
        )).one();
        if (r == null) {
            embeddingChunkHashCache.put(cacheKey, Optional.empty());
            return Optional.empty();
        }
        var row = rowToEmbedding(r);
        embeddingChunkHashCache.put(cacheKey, Optional.of(row.embedding()));
        return Optional.of(row);
    }

    public List<EmbeddingRow> listEmbeddings(String tenantId, UUID contentRefId) {
        var rs = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.embedding_content_cache WHERE tenant_id=? AND content_ref_id=?",
            tenantId, contentRefId
        ));
        List<EmbeddingRow> rows = new ArrayList<>();
        for (Row r : rs) rows.add(rowToEmbedding(r));
        return rows;
    }

    private EmbeddingRow rowToEmbedding(Row r) {
        ByteBuffer buf = r.getByteBuffer("embedding");
        float[] embedding = buf == null ? new float[0] : LZ4EmbeddingCodec.decompress(buf.array());
        return new EmbeddingRow(
            r.getString("tenant_id"), r.getUuid("content_ref_id"),
            r.getInt("chunk_ordinal"), r.getString("model_id"),
            r.getString("chunk_sha256"), embedding, r.getInt("embedding_dim"),
            r.getInstant("created_at")
        );
    }

    // ---- Annotations (v1.24/1.25 AAP-1) ----

    public void insertAnnotation(AnnotationRow row) {
        session.execute(annotationInsertStatement(row));
    }

    public void insertAnnotations(List<AnnotationRow> rows) {
        var batch = BatchStatement.newInstance(DefaultBatchType.LOGGED);
        for (AnnotationRow row : rows) {
            batch = batch.add(annotationInsertStatement(row));
        }
        session.execute(batch);
    }

    private static SimpleStatement annotationInsertStatement(AnnotationRow row) {
        return SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.annotations " +
            "(tenant_id, target_type, target_id, annotation_id, definition_id, definition_version, " +
            "annotation_type, namespace, name, value, producer, producer_version, confidence, " +
            "source_classification, representation_used, provenance, processing_run_id, created_at, invalidated_at) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            row.tenantId(), row.targetType(), row.targetId(), row.annotationId(),
            row.definitionId(), row.definitionVersion(), row.annotationType(), row.namespace(),
            row.name(), row.value(), row.producer(), row.producerVersion(), row.confidence(),
            new java.util.HashSet<>(row.sourceClassification()), row.representationUsed(),
            row.provenance(), row.processingRunId(), row.createdAt(), row.invalidatedAt()
        );
    }

    public List<AnnotationRow> readAnnotations(String tenantId, String targetType, String targetId) {
        var result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.annotations WHERE tenant_id=? AND target_type=? AND target_id=?",
            tenantId, targetType, targetId
        ));
        List<AnnotationRow> rows = new ArrayList<>();
        for (Row r : result) {
            rows.add(toAnnotationRow(r));
        }
        return rows;
    }

    /**
     * All non-invalidated annotation instances for a definition, across every target -
     * Resolutor's (AAP-2) entry point for building a recalculation plan. {@code definition_id}
     * is not part of the partition key, so this scans within the tenant like
     * {@link #readAnalysisByInputHash} does; acceptable at the current corpus scale (§49 of
     * docs/implementation/annotations-analytics-plane/02-recalculation.md).
     */
    public List<AnnotationRow> readAnnotationsByDefinition(String tenantId, String definitionId) {
        var result = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.annotations WHERE tenant_id=? AND definition_id=? ALLOW FILTERING",
            tenantId, definitionId
        ));
        List<AnnotationRow> rows = new ArrayList<>();
        for (Row r : result) {
            rows.add(toAnnotationRow(r));
        }
        return rows;
    }

    /** Marks a stale annotation instance superseded without deleting it (design §52, §75). */
    public void invalidateAnnotation(String tenantId, String targetType, String targetId, UUID annotationId, Instant invalidatedAt) {
        session.execute(SimpleStatement.newInstance(
            "UPDATE ingestion_cache.annotations SET invalidated_at=? " +
            "WHERE tenant_id=? AND target_type=? AND target_id=? AND annotation_id=?",
            invalidatedAt, tenantId, targetType, targetId, annotationId
        ));
    }

    private AnnotationRow toAnnotationRow(Row r) {
        return new AnnotationRow(
            r.getString("tenant_id"), r.getString("target_type"), r.getString("target_id"),
            r.getUuid("annotation_id"), r.getString("definition_id"), intOrDefault(r, "definition_version", 0),
            r.getString("annotation_type"), r.getString("namespace"), r.getString("name"),
            r.getString("value"), r.getString("producer"), r.getString("producer_version"),
            doubleOrDefault(r, "confidence", 0.0),
            setOrDefault(r, "source_classification", AnnotationRow.PUBLIC_ONLY),
            stringOrEmpty(r, "representation_used"), stringOrEmpty(r, "provenance"),
            r.getUuid("processing_run_id"), r.getInstant("created_at"), r.getInstant("invalidated_at")
        );
    }

    private static double doubleOrDefault(Row r, String column, double fallback) {
        try {
            if (r.isNull(column)) {
                return fallback;
            }
            return r.getDouble(column);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ---- Outbox (Phase 3) ----

    public void insertOutboxRow(OutboxRow row) {
        registerTenant(row.tenantId(), row.createdAt());
        session.execute(SimpleStatement.newInstance(
            "INSERT INTO ingestion_cache.manifest_transitions_outbox " +
            "(tenant_id, event_id, manifest_id, transition_from, transition_to, topic, payload_json, published, " +
            "created_at, published_at, attempt_count, last_error, next_attempt_at, dead_lettered) " +
            "VALUES (?, now(), ?, ?, ?, ?, ?, false, ?, null, 0, null, null, false)",
            row.tenantId(), row.manifestId(), row.transitionFrom(), row.transitionTo(),
            row.topic(), row.payloadJson(), row.createdAt()
        ));
    }

    public List<OutboxRow> listUnpublishedOutbox(String tenantId, int limit, Instant now) {
        var rs = session.execute(SimpleStatement.newInstance(
            "SELECT * FROM ingestion_cache.manifest_transitions_outbox WHERE tenant_id=? AND published=false LIMIT ? ALLOW FILTERING",
            tenantId, limit
        ));
        List<OutboxRow> rows = new ArrayList<>();
        for (Row r : rs) {
            OutboxRow row = rowToOutbox(r);
            if (!row.deadLettered() && (row.nextAttemptAt() == null || !row.nextAttemptAt().isAfter(now))) {
                rows.add(row);
            }
        }
        return rows;
    }

    public void markOutboxPublished(String tenantId, UUID eventId, Instant publishedAt) {
        session.execute(SimpleStatement.newInstance(
            "UPDATE ingestion_cache.manifest_transitions_outbox SET published=true, published_at=?, " +
            "last_error=null, next_attempt_at=null WHERE tenant_id=? AND event_id=?",
            publishedAt, tenantId, eventId
        ));
    }

    public void recordOutboxFailure(OutboxRow row, int attemptCount, String error, Instant nextAttemptAt,
            boolean deadLettered, Instant occurredAt) {
        session.execute(SimpleStatement.newInstance(
            "UPDATE ingestion_cache.manifest_transitions_outbox SET attempt_count=?, last_error=?, " +
            "next_attempt_at=?, dead_lettered=? WHERE tenant_id=? AND event_id=?",
            attemptCount, error, nextAttemptAt, deadLettered, row.tenantId(), row.eventId()
        ));
        if (deadLettered) {
            session.execute(SimpleStatement.newInstance(
                "INSERT INTO ingestion_cache.dead_letter_events " +
                "(tenant_id, event_id, event_type, aggregate_type, aggregate_id, payload_json, created_at, " +
                "dead_lettered_at, attempt_count, last_error) VALUES (?,?,?,?,?,?,?,?,?,?)",
                row.tenantId(), row.eventId(), row.topic(), "Manifest", row.manifestId().toString(),
                row.payloadJson(), row.createdAt(), occurredAt, attemptCount, error
            ));
        }
    }

    private OutboxRow rowToOutbox(Row r) {
        return new OutboxRow(
            r.getString("tenant_id"),
            r.getUuid("event_id"),
            r.getUuid("manifest_id"),
            r.getString("transition_from"),
            r.getString("transition_to"),
            r.getString("topic"),
            r.getString("payload_json"),
            r.getBoolean("published"),
            r.getInstant("created_at"),
            r.getInstant("published_at"),
            intOrDefault(r, "attempt_count", 0),
            r.getString("last_error"),
            r.getInstant("next_attempt_at"),
            boolOrDefault(r, "dead_lettered", false)
        );
    }

    public CqlSession getSession() { return session; }
}
