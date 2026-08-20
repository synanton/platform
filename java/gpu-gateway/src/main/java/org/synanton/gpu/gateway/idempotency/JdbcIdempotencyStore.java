package org.synanton.gpu.gateway.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.synanton.gpu.gateway.config.GpuGatewayProperties;
import org.synanton.gpu.v1.ExecutionResponse;
import org.synanton.gpu.v1.ExecutionState;
import org.synanton.gpu.v1.ExecutionStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Component
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcIdempotencyStore.class);

    private static final String SQL_GET_BY_REQUEST_ID =
            "SELECT request_id, execution_id, state, response FROM gpu_idempotency_store WHERE request_id = ?";

    private static final String SQL_GET_BY_EXECUTION_ID =
            "SELECT request_id, execution_id, state, response FROM gpu_idempotency_store WHERE execution_id = ?";

    private static final String SQL_INSERT =
            "INSERT INTO gpu_idempotency_store (request_id, execution_id, state, expires_at) VALUES (?, ?, 'QUEUED', ?)";

    private static final String SQL_COMPLETE =
            "UPDATE gpu_idempotency_store SET state = ?, response = ?, updated_at = NOW() WHERE request_id = ?";

    private final JdbcTemplate jdbc;
    private final int retentionHours;

    public JdbcIdempotencyStore(JdbcTemplate jdbc, GpuGatewayProperties properties) {
        this.jdbc = jdbc;
        this.retentionHours = properties.getIdempotency().getRetentionHours();
    }

    @Override
    public Optional<ExecutionResponse> get(String requestId) {
        try {
            return jdbc.query(SQL_GET_BY_REQUEST_ID, rs -> {
                if (!rs.next()) return Optional.empty();
                byte[] responseBytes = rs.getBytes("response");
                if (responseBytes == null) return Optional.empty();
                try {
                    return Optional.of(ExecutionResponse.parseFrom(responseBytes));
                } catch (Exception e) {
                    log.error("Failed to deserialise stored ExecutionResponse for request_id={}", requestId, e);
                    return Optional.empty();
                }
            }, requestId);
        } catch (Exception e) {
            throw new IdempotencyStoreUnavailableException("Idempotency store unavailable for request_id=" + requestId, e);
        }
    }

    @Override
    public void initiate(String requestId, String executionId) {
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(retentionHours, ChronoUnit.HOURS));
        try {
            jdbc.update(SQL_INSERT, requestId, executionId, expiresAt);
        } catch (DuplicateKeyException e) {
            // Concurrent retry: another thread already initiated; caller should re-check get()
            throw new DuplicateRequestIdException("request_id already exists: " + requestId, e);
        } catch (Exception e) {
            throw new IdempotencyStoreUnavailableException("Failed to initiate idempotency record for request_id=" + requestId, e);
        }
    }

    @Override
    public void complete(String requestId, ExecutionResponse response) {
        try {
            String state = response.getState().name();
            byte[] responseBytes = response.toByteArray();
            jdbc.update(SQL_COMPLETE, state, responseBytes, requestId);
        } catch (Exception e) {
            log.warn("Failed to persist execution result for request_id={}; idempotency may be lost on retry", requestId, e);
        }
    }

    @Override
    public Optional<ExecutionStatus> getByExecutionId(String executionId) {
        try {
            return jdbc.query(SQL_GET_BY_EXECUTION_ID, rs -> {
                if (!rs.next()) return Optional.empty();
                String requestId = rs.getString("request_id");
                String stateStr = rs.getString("state");
                ExecutionState state = parseState(stateStr);
                byte[] responseBytes = rs.getBytes("response");
                ExecutionStatus.Builder builder = ExecutionStatus.newBuilder()
                        .setRequestId(requestId)
                        .setExecutionId(executionId)
                        .setState(state);
                if (responseBytes != null) {
                    try {
                        ExecutionResponse stored = ExecutionResponse.parseFrom(responseBytes);
                        if (stored.hasError()) builder.setError(stored.getError());
                        if (stored.hasUsage()) builder.setUsage(stored.getUsage());
                    } catch (Exception e) {
                        log.warn("Failed to deserialise response for execution_id={}", executionId, e);
                    }
                }
                return Optional.of(builder.build());
            }, executionId);
        } catch (Exception e) {
            throw new IdempotencyStoreUnavailableException("Idempotency store unavailable for execution_id=" + executionId, e);
        }
    }

    private ExecutionState parseState(String state) {
        try {
            return ExecutionState.valueOf(state);
        } catch (IllegalArgumentException e) {
            return ExecutionState.EXECUTION_STATE_UNSPECIFIED;
        }
    }

    public static class IdempotencyStoreUnavailableException extends RuntimeException {
        public IdempotencyStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class DuplicateRequestIdException extends RuntimeException {
        public DuplicateRequestIdException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
