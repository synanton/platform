package org.synanton.gpu.gateway.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.synanton.gpu.gateway.config.GpuGatewayProperties;
import org.synanton.gpu.v1.ExecutionResponse;
import org.synanton.gpu.v1.ExecutionState;
import org.synanton.gpu.v1.ExecutionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

// Uses H2 in-memory database; schema applied inline to avoid Flyway dependency in unit tests.
class JdbcIdempotencyStoreTest {

    private JdbcTemplate jdbc;
    private JdbcIdempotencyStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:testdb-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS gpu_idempotency_store (
                    request_id   TEXT        NOT NULL,
                    execution_id TEXT        NOT NULL,
                    state        TEXT        NOT NULL DEFAULT 'QUEUED',
                    response     BINARY LARGE OBJECT,
                    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
                    updated_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
                    expires_at   TIMESTAMP   NOT NULL,
                    CONSTRAINT pk_gpu_idempotency  PRIMARY KEY (request_id),
                    CONSTRAINT uq_gpu_execution_id UNIQUE (execution_id)
                )
                """);
        GpuGatewayProperties props = new GpuGatewayProperties();
        store = new JdbcIdempotencyStore(jdbc, props);
    }

    @Test
    void get_notFound_returnsEmpty() {
        assertThat(store.get("req-missing")).isEmpty();
    }

    @Test
    void initiate_createsQueuedRecord() {
        store.initiate("req-1", "exec-1");

        String state = jdbc.queryForObject(
                "SELECT state FROM gpu_idempotency_store WHERE request_id = ?", String.class, "req-1");
        assertThat(state).isEqualTo("QUEUED");
    }

    @Test
    void complete_storesSerializedResponse() {
        store.initiate("req-2", "exec-2");

        ExecutionResponse response = ExecutionResponse.newBuilder()
                .setRequestId("req-2")
                .setExecutionId("exec-2")
                .setState(ExecutionState.SUCCESS)
                .build();
        store.complete("req-2", response);

        Optional<ExecutionResponse> retrieved = store.get("req-2");
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getExecutionId()).isEqualTo("exec-2");
        assertThat(retrieved.get().getState()).isEqualTo(ExecutionState.SUCCESS);
    }

    @Test
    void getByExecutionId_returnsStatus() {
        store.initiate("req-3", "exec-3");

        Optional<ExecutionStatus> status = store.getByExecutionId("exec-3");
        assertThat(status).isPresent();
        assertThat(status.get().getExecutionId()).isEqualTo("exec-3");
        assertThat(status.get().getRequestId()).isEqualTo("req-3");
        assertThat(status.get().getState()).isEqualTo(ExecutionState.QUEUED);
    }

    @Test
    void initiate_duplicateRequestId_throwsDuplicateRequestIdException() {
        store.initiate("req-dup", "exec-dup-1");

        assertThatThrownBy(() -> store.initiate("req-dup", "exec-dup-2"))
                .isInstanceOf(JdbcIdempotencyStore.DuplicateRequestIdException.class);
    }

    @Test
    void getByExecutionId_notFound_returnsEmpty() {
        assertThat(store.getByExecutionId("exec-ghost")).isEmpty();
    }
}
