package org.synanton.ingestioncache.claim;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.synanton.common.resolution.StaleOwnershipException;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;

/**
 * ADR-029 §12.4 revision ownership. The ownership sentinel lives in the same
 * Cassandra partition as the promotion rows, so row writes and closure are
 * serialized by same-partition lightweight transactions.
 */
public final class PromotionOwnershipStore {

    public static final String OWNERSHIP_RELATION_ID = "__OWNERSHIP__";

    private final CqlSession session;

    public PromotionOwnershipStore(CqlSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public record OwnershipToken(String producerId, long generation) {
        public OwnershipToken {
            Objects.requireNonNull(producerId, "producerId");
            if (producerId.isBlank()) {
                throw new IllegalArgumentException("producerId must not be blank");
            }
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be positive");
            }
        }
    }

    public record OwnershipRow(
            String tenantId,
            UUID sourceVersionId,
            String ownerProducerId,
            long ownerGeneration,
            boolean closed,
            Instant startedAt,
            Instant closedAt,
            int takeoverCount,
            Instant lastWriteAt) {
    }

    public Optional<OwnershipToken> acquire(String tenantId, UUID sourceVersionId,
                                            String producerId) {
        requireKey(tenantId, sourceVersionId);
        Objects.requireNonNull(producerId, "producerId");
        Instant now = Instant.now();
        ResultSet result = session.execute(SimpleStatement.newInstance("""
                INSERT INTO ingestion_cache.relation_promotion_results
                    (tenant_id, source_version_id, relation_id, owner_producer_id,
                     owner_generation, owner_closed, owner_started_at, owner_takeover_count,
                     owner_last_write_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                IF NOT EXISTS
                """, tenantId, sourceVersionId, OWNERSHIP_RELATION_ID, producerId,
                1L, false, now, 0, now));
        if (result.wasApplied()) {
            return Optional.of(new OwnershipToken(producerId, 1L));
        }
        return read(tenantId, sourceVersionId)
                .filter(row -> !row.closed())
                .filter(row -> producerId.equals(row.ownerProducerId()))
                .map(row -> new OwnershipToken(row.ownerProducerId(), row.ownerGeneration()));
    }

    public boolean writeFenced(String tenantId, UUID sourceVersionId,
                               SimpleStatement rowInsert, OwnershipToken token) {
        requireKey(tenantId, sourceVersionId);
        Objects.requireNonNull(rowInsert, "rowInsert");
        Objects.requireNonNull(token, "token");
        BatchStatement batch = BatchStatement.newInstance(DefaultBatchType.LOGGED)
                .add(rowInsert)
                .add(SimpleStatement.newInstance("""
                        UPDATE ingestion_cache.relation_promotion_results
                        SET owner_last_write_at=toTimestamp(now())
                        WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                        IF owner_producer_id=? AND owner_generation=? AND owner_closed=false
                        """, tenantId, sourceVersionId, OWNERSHIP_RELATION_ID,
                        token.producerId(), token.generation()));
        ResultSet result = session.execute(batch);
        if (!result.wasApplied()) {
            throw new StaleOwnershipException("Promotion row write rejected for tenant="
                    + tenantId + " version=" + sourceVersionId + " producer="
                    + token.producerId() + " generation=" + token.generation());
        }
        return true;
    }

    /**
     * The only production writer of promotion result rows. VALID rows become
     * claimable promotion work; INVALID rows are terminal.
     */
    public boolean insertPromotionResult(RelationPromotionResultRow row, OwnershipToken token) {
        Objects.requireNonNull(row, "row");
        if (OWNERSHIP_RELATION_ID.equals(row.relationId())) {
            throw new IllegalArgumentException("ownership sentinel is managed separately");
        }
        String claimStatus = "VALID".equals(row.promotionStatus()) ? "PENDING" : "COMPLETED";
        SimpleStatement insert = SimpleStatement.newInstance("""
                INSERT INTO ingestion_cache.relation_promotion_results
                    (tenant_id, source_version_id, relation_id, subject_mention_id,
                     object_mention_id, predicate, promotion_status, canonical_subject_id,
                     canonical_object_id, reason, evidence_json, strategy_version,
                     ontology_version, decided_at, superseded_at, claim_status,
                     attempt_count, source_id, revision_id, generation,
                     extraction_run_id, identity_status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, row.tenantId(), row.sourceVersionId(), row.relationId(),
                row.subjectMentionId(), row.objectMentionId(), row.predicate(),
                row.promotionStatus(), row.canonicalSubjectId(), row.canonicalObjectId(),
                row.reason(), row.evidenceJson(), row.strategyVersion(),
                row.ontologyVersion(), row.decidedAt(), row.supersededAt(), claimStatus, 0,
                row.sourceId(), row.revisionId(),
                "CURRENT".equals(row.identityStatus()) ? row.generation() : null,
                row.extractionRunId(), row.identityStatus());
        return writeFenced(row.tenantId(), row.sourceVersionId(), insert, token);
    }

    /**
     * Consumer outcome update. It may run before or after producer closure because
     * it does not add or remove rows; it is fenced only by owner generation.
     */
    public boolean updateOutcomeFenced(String tenantId, UUID sourceVersionId,
                                       String relationId, String outcome, OwnershipToken token) {
        requireKey(tenantId, sourceVersionId);
        Objects.requireNonNull(relationId, "relationId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(token, "token");
        BatchStatement batch = BatchStatement.newInstance(DefaultBatchType.LOGGED)
                .add(SimpleStatement.newInstance("""
                        UPDATE ingestion_cache.relation_promotion_results
                        SET promotion_status=?
                        WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                        """, outcome, tenantId, sourceVersionId, relationId))
                .add(SimpleStatement.newInstance("""
                        UPDATE ingestion_cache.relation_promotion_results
                        SET owner_last_write_at=toTimestamp(now())
                        WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                        IF owner_producer_id=? AND owner_generation=?
                        """, tenantId, sourceVersionId, OWNERSHIP_RELATION_ID,
                        token.producerId(), token.generation()));
        ResultSet result = session.execute(batch);
        if (!result.wasApplied()) {
            throw new StaleOwnershipException("Promotion outcome update rejected for tenant="
                    + tenantId + " version=" + sourceVersionId + " producer="
                    + token.producerId() + " generation=" + token.generation());
        }
        return true;
    }

    public void closeSet(String tenantId, UUID sourceVersionId, OwnershipToken token) {
        requireKey(tenantId, sourceVersionId);
        Objects.requireNonNull(token, "token");
        Instant now = Instant.now();
        ResultSet result = session.execute(SimpleStatement.newInstance("""
                UPDATE ingestion_cache.relation_promotion_results
                SET owner_closed=true, owner_closed_at=?, owner_last_write_at=?
                WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                IF owner_producer_id=? AND owner_generation=? AND owner_closed=false
                """, now, now, tenantId, sourceVersionId, OWNERSHIP_RELATION_ID,
                token.producerId(), token.generation()));
        if (!result.wasApplied()) {
            throw new StaleOwnershipException("Promotion set close rejected for tenant="
                    + tenantId + " version=" + sourceVersionId + " producer="
                    + token.producerId() + " generation=" + token.generation());
        }
    }

    public Optional<OwnershipToken> takeover(String tenantId, UUID sourceVersionId,
                                             String newProducerId, long expectedGeneration) {
        requireKey(tenantId, sourceVersionId);
        Objects.requireNonNull(newProducerId, "newProducerId");
        OwnershipRow existing = read(tenantId, sourceVersionId).orElse(null);
        if (existing == null || existing.closed()
                || existing.ownerGeneration() != expectedGeneration) {
            return Optional.empty();
        }
        long nextGeneration = expectedGeneration + 1;
        int nextTakeoverCount = existing.takeoverCount() + 1;
        Instant now = Instant.now();
        ResultSet result = session.execute(SimpleStatement.newInstance("""
                UPDATE ingestion_cache.relation_promotion_results
                SET owner_producer_id=?, owner_generation=?, owner_started_at=?,
                    owner_closed=false, owner_closed_at=null, owner_takeover_count=?,
                    owner_last_write_at=?
                WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                IF owner_generation=? AND owner_closed=false
                """, newProducerId, nextGeneration, now, nextTakeoverCount, now,
                tenantId, sourceVersionId, OWNERSHIP_RELATION_ID, expectedGeneration));
        if (!result.wasApplied()) {
            return Optional.empty();
        }
        return Optional.of(new OwnershipToken(newProducerId, nextGeneration));
    }

    public Optional<OwnershipRow> read(String tenantId, UUID sourceVersionId) {
        requireKey(tenantId, sourceVersionId);
        Row row = session.execute(SimpleStatement.newInstance("""
                SELECT * FROM ingestion_cache.relation_promotion_results
                WHERE tenant_id=? AND source_version_id=? AND relation_id=?
                """, tenantId, sourceVersionId, OWNERSHIP_RELATION_ID)).one();
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new OwnershipRow(
                row.getString("tenant_id"), row.getUuid("source_version_id"),
                row.getString("owner_producer_id"),
                row.isNull("owner_generation") ? 0L : row.getLong("owner_generation"),
                Boolean.TRUE.equals(row.getBoolean("owner_closed")),
                row.getInstant("owner_started_at"), row.getInstant("owner_closed_at"),
                row.isNull("owner_takeover_count") ? 0 : row.getInt("owner_takeover_count"),
                row.getInstant("owner_last_write_at")));
    }

    public boolean isClosed(String tenantId, UUID sourceVersionId) {
        return read(tenantId, sourceVersionId).map(OwnershipRow::closed).orElse(false);
    }

    private static void requireKey(String tenantId, UUID sourceVersionId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        Objects.requireNonNull(sourceVersionId, "sourceVersionId");
    }
}
