package org.synanton.ingestioncache.compliance;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable deletion audit for the Phase 2a scope. */
public final class ComplianceDeletionAuditStore {

    private final CqlSession session;

    public ComplianceDeletionAuditStore(CqlSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public void record(ComplianceDeletionEvent event) {
        Objects.requireNonNull(event, "event");
        session.execute(SimpleStatement.newInstance("""
                INSERT INTO ingestion_cache.compliance_deletion_audit
                    (tenant_id, source_version_id, event_time, event_id, event_type,
                     record_scope, deleted_count, actor, reason, detail)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, event.tenantId(), event.sourceVersionId(), event.eventTime(),
                event.eventId(), event.eventType(), event.recordScope(),
                event.deletedCount(), event.actor(), event.reason(), event.detail()));
    }

    public List<ComplianceDeletionEvent> events(String tenantId, UUID sourceVersionId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
        List<ComplianceDeletionEvent> out = new ArrayList<>();
        for (Row row : session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.compliance_deletion_audit
                WHERE tenant_id=? AND source_version_id=?
                """, tenantId, sourceVersionId))) {
            out.add(new ComplianceDeletionEvent(
                    row.getString("tenant_id"), row.getUuid("source_version_id"),
                    row.getInstant("event_time"), row.getUuid("event_id"),
                    row.getString("event_type"), row.getString("record_scope"),
                    row.getInt("deleted_count"), row.getString("actor"),
                    row.getString("reason"), row.getString("detail")));
        }
        return out;
    }

    public static ComplianceDeletionEvent event(String tenantId, UUID sourceVersionId,
                                                String eventType, String recordScope,
                                                int deletedCount, String actor, String reason,
                                                String detail) {
        return new ComplianceDeletionEvent(tenantId, sourceVersionId, Instant.now(),
                UUID.randomUUID(), eventType, recordScope, deletedCount, actor, reason, detail);
    }
}
