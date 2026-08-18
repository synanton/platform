package org.synanton.synt.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SessionPinRepository {

    public record SessionPinRecord(
            UUID pinId,
            String tenantId,
            String version,
            String subjectId,
            Instant pinnedAt,
            Instant expiresAt
    ) {}

    private static final RowMapper<SessionPinRecord> ROW_MAPPER = (rs, rowNum) -> new SessionPinRecord(
            rs.getObject("pin_id", UUID.class),
            rs.getString("tenant_id"),
            rs.getString("version"),
            rs.getString("subject_id"),
            rs.getTimestamp("pinned_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant()
    );

    private final JdbcTemplate jdbcTemplate;

    public SessionPinRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns the pinned version string if the pin is still active (expires_at > now()).
     */
    public Optional<String> findActivePin(String tenantId, String subjectId) {
        List<String> results = jdbcTemplate.query(
                "SELECT version FROM syntology.session_pins" +
                        " WHERE tenant_id = ? AND subject_id = ? AND expires_at > CURRENT_TIMESTAMP",
                (rs, rn) -> rs.getString("version"),
                tenantId,
                subjectId
        );
        return results.stream().findFirst();
    }

    /**
     * Insert or update a session pin for (tenant_id, subject_id).
     */
    public void upsert(String tenantId, String subjectId, String version, Instant expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO syntology.session_pins (tenant_id, subject_id, version, pinned_at, expires_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)
                ON CONFLICT (tenant_id, subject_id)
                DO UPDATE SET version = EXCLUDED.version,
                              pinned_at = CURRENT_TIMESTAMP,
                              expires_at = EXCLUDED.expires_at
                """,
                tenantId,
                subjectId,
                version,
                Timestamp.from(expiresAt)
        );
    }

    /**
     * Delete the pin for (tenant_id, subject_id). Returns true if a row was deleted.
     */
    public boolean delete(String tenantId, String subjectId) {
        int affected = jdbcTemplate.update(
                "DELETE FROM syntology.session_pins WHERE tenant_id = ? AND subject_id = ?",
                tenantId,
                subjectId
        );
        return affected > 0;
    }

    /**
     * Delete all expired pins. Returns the number of rows deleted.
     */
    public int deleteExpired() {
        return jdbcTemplate.update(
                "DELETE FROM syntology.session_pins WHERE expires_at < CURRENT_TIMESTAMP"
        );
    }

    /**
     * Returns the full pin record regardless of expiry (for GET endpoint).
     */
    public Optional<SessionPinRecord> findPin(String tenantId, String subjectId) {
        List<SessionPinRecord> results = jdbcTemplate.query(
                "SELECT pin_id, tenant_id, version, subject_id, pinned_at, expires_at" +
                        " FROM syntology.session_pins WHERE tenant_id = ? AND subject_id = ?",
                ROW_MAPPER,
                tenantId,
                subjectId
        );
        return results.stream().findFirst();
    }
}
