package org.synanton.ingestioncache.activation;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Cassandra LWT-backed activation record and current pointer. */
public final class CassandraSourceActivationStore implements SourceActivationStore {

    private final CqlSession session;

    public CassandraSourceActivationStore(CqlSession session) {
        this.session = session;
    }

    @Override
    public boolean stage(ActivationRecord record) {
        ResultSet inserted = session.execute(SimpleStatement.newInstance("""
                INSERT INTO ingestion_cache.source_activation
                    (tenant_id, source_id, generation, revision_id, connector_id, resource_id,
                     previous_revision_id, state, content_hash, graph_applied_generation,
                     graph_applied_state, search_applied_generation, search_applied_state,
                     last_error, retry_eligible, created_at, updated_at, activated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                IF NOT EXISTS
                """, record.tenantId(), record.sourceId(), record.generation(), record.revisionId(),
                record.connectorId(), record.resourceId(), record.previousRevisionId(),
                record.state().name(), record.contentHash(), record.graphAppliedGeneration(),
                record.graphAppliedState(), record.searchAppliedGeneration(),
                record.searchAppliedState(), record.lastError(), record.retryEligible(),
                record.createdAt(), record.updatedAt(), record.activatedAt()));
        if (!inserted.wasApplied()) {
            return false;
        }
        session.execute(SimpleStatement.newInstance("""
                INSERT INTO ingestion_cache.source_current
                    (tenant_id, source_id, current_generation, active_revision_id,
                     pending_generation, pending_revision_id, updated_at)
                VALUES (?,?,?,?,?,?,?)
                IF NOT EXISTS
                """, record.tenantId(), record.sourceId(), 0L, null,
                record.generation(), record.revisionId(), record.updatedAt()));
        Row currentRow = session.execute(SimpleStatement.newInstance("""
                SELECT pending_generation FROM ingestion_cache.source_current
                WHERE tenant_id=? AND source_id=?
                """, record.tenantId(), record.sourceId())).one();
        if (currentRow == null || currentRow.isNull("pending_generation")
                || currentRow.getLong("pending_generation") < record.generation()) {
            session.execute(SimpleStatement.newInstance("""
                    UPDATE ingestion_cache.source_current
                    SET pending_generation=?, pending_revision_id=?, updated_at=?
                    WHERE tenant_id=? AND source_id=?
                    """, record.generation(), record.revisionId(), record.updatedAt(),
                    record.tenantId(), record.sourceId()));
        }
        return true;
    }

    @Override
    public Optional<ActivationRecord> read(String tenantId, String sourceId, long generation) {
        Row row = session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.source_activation
                WHERE tenant_id=? AND source_id=? AND generation=?
                """, tenantId, sourceId, generation)).one();
        return row == null ? Optional.empty() : Optional.of(fromRow(row));
    }

    @Override
    public Optional<ActivationRecord> readLatest(String tenantId, String sourceId) {
        Row row = session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.source_activation
                WHERE tenant_id=? AND source_id=? LIMIT 1
                """, tenantId, sourceId)).one();
        return row == null ? Optional.empty() : Optional.of(fromRow(row));
    }

    @Override
    public Optional<SourceLifecycleRecord> readLifecycle(String tenantId, String sourceId) {
        Row row = session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.source_current WHERE tenant_id=? AND source_id=?
                """, tenantId, sourceId)).one();
        if (row == null) {
            return Optional.empty();
        }
        Optional<ActivationRecord> latest = readLatest(tenantId, sourceId);
        long accepted = latest.map(ActivationRecord::generation).orElse(0L);
        long current = row.isNull("current_generation") ? 0L : row.getLong("current_generation");
        UUID activeRevision = row.getUuid("active_revision_id");
        UUID pendingRevision = row.getUuid("pending_revision_id");
        ActivationRecord stateRecord = latest.orElse(null);
        ActivationState state = stateRecord == null ? null : stateRecord.state();
        if (stateRecord != null && current > 0 && stateRecord.generation() == current) {
            state = ActivationState.CURRENT;
        }
        return Optional.of(new SourceLifecycleRecord(tenantId, sourceId, accepted, current,
                activeRevision, pendingRevision, state,
                stateRecord == null ? null : stateRecord.lastError(),
                stateRecord != null && stateRecord.retryEligible(),
                row.getInstant("updated_at")));
    }

    @Override
    public boolean transition(String tenantId, String sourceId, long generation,
                              ActivationState expected, ActivationState next,
                              ProjectionMarkers markers, String error,
                              boolean retryEligible, Instant now) {
        if (!expected.canTransitionTo(next)) {
            throw new IllegalArgumentException("illegal activation transition " + expected + " -> " + next);
        }
        ResultSet result = session.execute(SimpleStatement.newInstance("""
                UPDATE ingestion_cache.source_activation
                SET state=?, graph_applied_generation=?, graph_applied_state=?,
                    search_applied_generation=?, search_applied_state=?,
                    last_error=?, retry_eligible=?, updated_at=?
                WHERE tenant_id=? AND source_id=? AND generation=?
                IF state=?
                """, next.name(), markers.graphGeneration(), markers.graphState(),
                markers.searchGeneration(), markers.searchState(), error, retryEligible, now,
                tenantId, sourceId, generation, expected.name()));
        return result.wasApplied();
    }

    @Override
    public boolean activate(String tenantId, String sourceId, long generation,
                            UUID revisionId, Instant now) {
        Optional<ActivationRecord> record = read(tenantId, sourceId, generation);
        if (record.isEmpty()) {
            return false;
        }
        ActivationState state = record.get().state();
        if (state == ActivationState.CURRENT) {
            return true;
        }
        if (state != ActivationState.SEARCH_COMMITTED) {
            return false;
        }
        ResultSet current = session.execute(SimpleStatement.newInstance("""
                UPDATE ingestion_cache.source_current
                SET current_generation=?, active_revision_id=?,
                    pending_generation=null, pending_revision_id=null, updated_at=?
                WHERE tenant_id=? AND source_id=?
                IF current_generation < ?
                """, generation, revisionId, now, tenantId, sourceId, generation));
        if (!current.wasApplied()) {
            Row row = session.execute(SimpleStatement.newInstance("""
                    SELECT current_generation FROM ingestion_cache.source_current
                    WHERE tenant_id=? AND source_id=?
                    """, tenantId, sourceId)).one();
            if (row == null || row.isNull("current_generation")
                    || row.getLong("current_generation") != generation) {
                return false;
            }
        }
        ResultSet marked = session.execute(SimpleStatement.newInstance("""
                UPDATE ingestion_cache.source_activation
                SET state=?, activated_at=?, updated_at=?, retry_eligible=false, last_error=null
                WHERE tenant_id=? AND source_id=? AND generation=?
                IF state=?
                """, ActivationState.CURRENT.name(), now, now,
                tenantId, sourceId, generation, ActivationState.SEARCH_COMMITTED.name()));
        return marked.wasApplied() || read(tenantId, sourceId, generation)
                .map(ActivationRecord::state)
                .filter(ActivationState.CURRENT::equals)
                .isPresent();
    }

    private static ActivationRecord fromRow(Row row) {
        return new ActivationRecord(
                row.getString("tenant_id"), row.getString("source_id"), row.getLong("generation"),
                row.getUuid("revision_id"), row.getString("connector_id"), row.getString("resource_id"),
                row.getUuid("previous_revision_id"), ActivationState.valueOf(row.getString("state")),
                row.getString("content_hash"),
                row.isNull("graph_applied_generation") ? 0L : row.getLong("graph_applied_generation"),
                row.getString("graph_applied_state"),
                row.isNull("search_applied_generation") ? 0L : row.getLong("search_applied_generation"),
                row.getString("search_applied_state"), row.getString("last_error"),
                Boolean.TRUE.equals(row.getBoolean("retry_eligible")),
                row.getInstant("created_at"), row.getInstant("updated_at"), row.getInstant("activated_at"));
    }
}
