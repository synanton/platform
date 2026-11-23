package org.synanton.ingestioncache.quarantine;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * #826 quarantine persistence.
 *
 * <p>The current-generation pointer is authoritative and updated by Cassandra
 * LWT. Generation rows use {@code STAGED}, {@code SUPERSEDED}, {@code FAILED},
 * or {@code DELETED}; "ACTIVE" is represented by
 * {@code quarantine_current_generation.current_generation}, not duplicated as a
 * second mutable status flag. That avoids a race between the pointer and a
 * status column.
 */
public final class QuarantineStore {

    public enum DeletionMode {
        TOMBSTONE,
        HARD_DELETE
    }

    public record PromotionResult(UUID currentGeneration, boolean applied) {}

    private final CqlSession session;

    public QuarantineStore(CqlSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public void createGeneration(QuarantineGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        session.execute(SimpleStatement.newInstance("""
                INSERT INTO ingestion_cache.quarantine_generations
                    (tenant_id, source_version_id, extraction_generation, source_document_id,
                     prompt_version, prompt_digest, schema_digest, model_id, model_revision,
                     extraction_started_at, extraction_run_id, generation_status,
                     superseded_by, promoted_at, promoted_by, promotion_reason,
                     previous_generation, deletion_mode, deleted_at, deleted_reason)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, generation.tenantId(), generation.sourceVersionId(),
                generation.extractionGeneration(), generation.sourceDocumentId(),
                generation.promptVersion(), generation.promptDigest(), generation.schemaDigest(),
                generation.modelId(), generation.modelRevision(), generation.extractionStartedAt(),
                generation.extractionRunId(), generation.generationStatus(),
                generation.supersededBy(), generation.promotedAt(), generation.promotedBy(),
                generation.promotionReason(), generation.previousGeneration(),
                generation.deletionMode(), generation.deletedAt(), generation.deletedReason()));
    }

    public void append(QuarantineRecord record) {
        Objects.requireNonNull(record, "record");
        session.execute(SimpleStatement.newInstance("""
                INSERT INTO ingestion_cache.quarantine_records
                    (tenant_id, source_version_id, extraction_generation, chunk_ordinal,
                     line_number, reason_code, category, detail, raw_line_sha256, raw_line,
                     captured_at, disposition, disposition_at, disposition_by,
                     disposition_reason, retention_expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, record.tenantId(), record.sourceVersionId(),
                record.extractionGeneration(), record.chunkOrdinal(), record.lineNumber(),
                record.reasonCode(), record.category(), record.detail(), record.rawLineSha256(),
                record.rawLine(), record.capturedAt(), record.disposition(),
                record.dispositionAt(), record.dispositionBy(), record.dispositionReason(),
                record.retentionExpiresAt()));
    }

    public Optional<UUID> currentGeneration(String tenantId, UUID sourceVersionId) {
        requireKey(tenantId, sourceVersionId);
        ResultSet result = session.execute(SimpleStatement.newInstance("""
                SELECT current_generation FROM ingestion_cache.quarantine_current_generation
                WHERE tenant_id=? AND source_version_id=?
                """, tenantId, sourceVersionId));
        Row row = result.one();
        return row == null ? Optional.empty() : Optional.ofNullable(row.getUuid("current_generation"));
    }

    public PromotionResult promote(String tenantId, UUID sourceVersionId, UUID generation,
                                   String actor, String reason) {
        requireKey(tenantId, sourceVersionId);
        Objects.requireNonNull(generation, "generation");
        Instant now = Instant.now();
        recordAudit(QuarantineAuditEvent.of(tenantId, sourceVersionId, generation,
                "PROMOTION_ATTEMPTED", actor, reason, null, now));
        for (int attempt = 0; attempt < 4; attempt++) {
            Optional<UUID> current = currentGeneration(tenantId, sourceVersionId);
            if (current.isPresent() && current.get().equals(generation)) {
                recordAudit(QuarantineAuditEvent.of(tenantId, sourceVersionId, generation,
                        "PROMOTION_IDEMPOTENT", actor, reason, "already current", now));
                return new PromotionResult(generation, true);
            }
            boolean applied;
            if (current.isEmpty()) {
                ResultSet result = session.execute(SimpleStatement.newInstance("""
                        INSERT INTO ingestion_cache.quarantine_current_generation
                            (tenant_id, source_version_id, current_generation,
                             generation_version, updated_at)
                        VALUES (?,?,?,?,?) IF NOT EXISTS
                        """, tenantId, sourceVersionId, generation, 1L, now));
                applied = result.wasApplied();
            } else {
                ResultSet result = session.execute(SimpleStatement.newInstance("""
                        UPDATE ingestion_cache.quarantine_current_generation
                        SET current_generation=?, generation_version=generation_version+1,
                            updated_at=?
                        WHERE tenant_id=? AND source_version_id=?
                        IF current_generation=?
                        """, generation, now, tenantId, sourceVersionId, current.get()));
                applied = result.wasApplied();
            }
            if (applied) {
                markPromoted(tenantId, sourceVersionId, generation, current.orElse(null),
                        actor, reason, now);
                recordAudit(QuarantineAuditEvent.of(tenantId, sourceVersionId, generation,
                        "PROMOTION_APPLIED", actor, reason,
                        current.map(value -> "previous=" + value).orElse("first promotion"), now));
                return new PromotionResult(generation, true);
            }
        }
        recordAudit(QuarantineAuditEvent.of(tenantId, sourceVersionId, generation,
                "PROMOTION_FAILED", actor, reason, "LWT did not converge", Instant.now()));
        throw new IllegalStateException("promotion did not converge for tenant=" + tenantId
                + " version=" + sourceVersionId + " generation=" + generation);
    }

    public List<QuarantineGeneration> generations(String tenantId, UUID sourceVersionId) {
        requireKey(tenantId, sourceVersionId);
        List<QuarantineGeneration> out = new ArrayList<>();
        for (Row row : session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.quarantine_generations
                WHERE tenant_id=? AND source_version_id=?
                """, tenantId, sourceVersionId))) {
            out.add(generation(row));
        }
        return out;
    }

    public List<QuarantineRecord> records(String tenantId, UUID sourceVersionId, UUID generation) {
        requireKey(tenantId, sourceVersionId);
        Objects.requireNonNull(generation, "generation");
        List<QuarantineRecord> out = new ArrayList<>();
        for (Row row : session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.quarantine_records
                WHERE tenant_id=? AND source_version_id=? AND extraction_generation=?
                """, tenantId, sourceVersionId, generation))) {
            out.add(record(row));
        }
        return out;
    }

    public List<QuarantineRecord> records(String tenantId, UUID sourceVersionId) {
        requireKey(tenantId, sourceVersionId);
        List<QuarantineRecord> out = new ArrayList<>();
        for (Row row : session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.quarantine_records
                WHERE tenant_id=? AND source_version_id=?
                """, tenantId, sourceVersionId))) {
            out.add(record(row));
        }
        return out;
    }

    /** Applies the deletion mode and returns the number of generations affected. */
    public int deleteSource(String tenantId, UUID sourceVersionId, DeletionMode mode, String reason) {
        requireKey(tenantId, sourceVersionId);
        Objects.requireNonNull(mode, "mode");
        List<QuarantineGeneration> generations = generations(tenantId, sourceVersionId);
        Instant now = Instant.now();
        if (mode == DeletionMode.TOMBSTONE) {
            for (QuarantineGeneration generation : generations) {
                session.execute(SimpleStatement.newInstance("""
                        UPDATE ingestion_cache.quarantine_generations
                        SET generation_status='DELETED', deletion_mode='TOMBSTONE',
                            deleted_at=?, deleted_reason=?
                        WHERE tenant_id=? AND source_version_id=? AND extraction_generation=?
                        """, now, reason, tenantId, sourceVersionId,
                        generation.extractionGeneration()));
            }
        } else {
            session.execute(SimpleStatement.newInstance("""
                    DELETE FROM ingestion_cache.quarantine_records
                    WHERE tenant_id=? AND source_version_id=?
                    """, tenantId, sourceVersionId));
            session.execute(SimpleStatement.newInstance("""
                    DELETE FROM ingestion_cache.quarantine_generations
                    WHERE tenant_id=? AND source_version_id=?
                    """, tenantId, sourceVersionId));
        }
        session.execute(SimpleStatement.newInstance("""
                DELETE FROM ingestion_cache.quarantine_current_generation
                WHERE tenant_id=? AND source_version_id=?
                """, tenantId, sourceVersionId));
        if (!generations.isEmpty()) {
            QuarantineGeneration first = generations.getFirst();
            recordAudit(QuarantineAuditEvent.of(tenantId, sourceVersionId,
                    first.extractionGeneration(),
                    mode == DeletionMode.TOMBSTONE ? "TOMBSTONE_APPLIED" : "HARD_DELETE_APPLIED",
                    "system", reason, "generations=" + generations.size(), now));
        }
        return generations.size();
    }

    public void recordAudit(QuarantineAuditEvent event) {
        Objects.requireNonNull(event, "event");
        session.execute(SimpleStatement.newInstance("""
                INSERT INTO ingestion_cache.quarantine_audit
                    (tenant_id, source_version_id, extraction_generation, event_time,
                     event_id, event_type, actor, reason, detail)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, event.tenantId(), event.sourceVersionId(), event.extractionGeneration(),
                event.eventTime(), event.eventId(), event.eventType(), event.actor(),
                event.reason(), event.detail()));
    }

    public List<QuarantineAuditEvent> auditEvents(String tenantId, UUID sourceVersionId,
                                                  UUID generation) {
        requireKey(tenantId, sourceVersionId);
        Objects.requireNonNull(generation, "generation");
        List<QuarantineAuditEvent> out = new ArrayList<>();
        for (Row row : session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.quarantine_audit
                WHERE tenant_id=? AND source_version_id=? AND extraction_generation=?
                """, tenantId, sourceVersionId, generation))) {
            out.add(new QuarantineAuditEvent(
                    row.getString("tenant_id"), row.getUuid("source_version_id"),
                    row.getUuid("extraction_generation"), row.getUuid("event_id"),
                    row.getInstant("event_time"), row.getString("event_type"),
                    row.getString("actor"), row.getString("reason"), row.getString("detail")));
        }
        return out;
    }

    private void markPromoted(String tenantId, UUID sourceVersionId, UUID generation,
                              UUID previous, String actor, String reason, Instant now) {
        BatchStatement batch = BatchStatement.newInstance(DefaultBatchType.LOGGED)
                .add(SimpleStatement.newInstance("""
                        UPDATE ingestion_cache.quarantine_generations
                        SET promoted_at=?, promoted_by=?, promotion_reason=?, previous_generation=?
                        WHERE tenant_id=? AND source_version_id=? AND extraction_generation=?
                        """, now, actor, reason, previous, tenantId, sourceVersionId, generation));
        if (previous != null) {
            batch = batch.add(SimpleStatement.newInstance("""
                    UPDATE ingestion_cache.quarantine_generations
                    SET generation_status='SUPERSEDED', superseded_by=?
                    WHERE tenant_id=? AND source_version_id=? AND extraction_generation=?
                    """, generation, tenantId, sourceVersionId, previous));
        }
        session.execute(batch);
    }

    public void markFailed(String tenantId, UUID sourceVersionId, UUID generation,
                           String actor, String reason) {
        requireKey(tenantId, sourceVersionId);
        Objects.requireNonNull(generation, "generation");
        Instant now = Instant.now();
        session.execute(SimpleStatement.newInstance("""
                UPDATE ingestion_cache.quarantine_generations
                SET generation_status='FAILED'
                WHERE tenant_id=? AND source_version_id=? AND extraction_generation=?
                """, tenantId, sourceVersionId, generation));
        recordAudit(QuarantineAuditEvent.of(tenantId, sourceVersionId, generation,
                "GENERATION_FAILED", actor, reason, null, now));
    }

    private static QuarantineGeneration generation(Row row) {
        return new QuarantineGeneration(
                row.getString("tenant_id"),
                row.getUuid("source_version_id"),
                row.getUuid("extraction_generation"),
                row.getString("source_document_id"),
                row.getString("prompt_version"),
                row.getString("prompt_digest"),
                row.getString("schema_digest"),
                row.getString("model_id"),
                row.getString("model_revision"),
                row.getInstant("extraction_started_at"),
                row.getUuid("extraction_run_id"),
                row.getString("generation_status"),
                row.getUuid("superseded_by"),
                row.getInstant("promoted_at"),
                row.getString("promoted_by"),
                row.getString("promotion_reason"),
                row.getUuid("previous_generation"),
                row.getString("deletion_mode"),
                row.getInstant("deleted_at"),
                row.getString("deleted_reason"));
    }

    private static QuarantineRecord record(Row row) {
        return new QuarantineRecord(
                row.getString("tenant_id"),
                row.getUuid("source_version_id"),
                row.getUuid("extraction_generation"),
                row.getInt("chunk_ordinal"),
                row.getInt("line_number"),
                row.getString("reason_code"),
                row.getString("category"),
                row.getString("detail"),
                row.getString("raw_line_sha256"),
                row.getString("raw_line"),
                row.getInstant("captured_at"),
                row.getString("disposition"),
                row.getInstant("disposition_at"),
                row.getString("disposition_by"),
                row.getString("disposition_reason"),
                row.getInstant("retention_expires_at"));
    }

    private static void requireKey(String tenantId, UUID sourceVersionId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
    }
}
