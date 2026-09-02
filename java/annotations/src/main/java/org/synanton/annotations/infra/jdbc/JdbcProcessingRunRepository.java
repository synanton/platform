package org.synanton.annotations.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.annotations.domain.model.ProcessingRun;
import org.synanton.annotations.domain.repository.ProcessingRunRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProcessingRunRepository implements ProcessingRunRepository {

    private final JdbcTemplate jdbc;

    public JdbcProcessingRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ProcessingRun insert(ProcessingRun run) {
        jdbc.update(
                """
                INSERT INTO annotations.processing_runs
                    (processing_run_id, producer, producer_version, tenant_id, definition_id,
                     definition_version, scope, started_at, ended_at, status, error_summary, resource_consumption_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                run.processingRunId(), run.producer(), run.producerVersion(), run.tenantId(),
                run.definitionId(), run.definitionVersion(), run.scope(), Timestamp.from(run.startedAt()),
                toTimestamp(run.endedAt()), run.status(), run.errorSummary(), run.resourceConsumptionJson()
        );
        return run;
    }

    @Override
    public Optional<ProcessingRun> findById(UUID processingRunId) {
        var rows = jdbc.query(
                """
                SELECT processing_run_id, producer, producer_version, tenant_id, definition_id,
                       definition_version, scope, started_at, ended_at, status, error_summary, resource_consumption_json
                FROM annotations.processing_runs WHERE processing_run_id = ?
                """,
                (rs, rowNum) -> toRun(rs), processingRunId
        );
        return rows.stream().findFirst();
    }

    @Override
    public void complete(UUID processingRunId, String status, Instant endedAt, String errorSummary, String resourceConsumptionJson) {
        jdbc.update(
                """
                UPDATE annotations.processing_runs
                SET status = ?, ended_at = ?, error_summary = ?, resource_consumption_json = ?
                WHERE processing_run_id = ?
                """,
                status, Timestamp.from(endedAt), errorSummary, resourceConsumptionJson, processingRunId
        );
    }

    private static ProcessingRun toRun(ResultSet rs) throws SQLException {
        Timestamp ended = rs.getTimestamp("ended_at");
        Object definitionVersion = rs.getObject("definition_version");
        return new ProcessingRun(
                rs.getObject("processing_run_id", UUID.class), rs.getString("producer"), rs.getString("producer_version"),
                rs.getString("tenant_id"), rs.getString("definition_id"),
                definitionVersion == null ? null : ((Number) definitionVersion).intValue(),
                rs.getString("scope"), rs.getTimestamp("started_at").toInstant(),
                ended == null ? null : ended.toInstant(), rs.getString("status"),
                rs.getString("error_summary"), rs.getString("resource_consumption_json")
        );
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
