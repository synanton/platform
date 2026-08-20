-- GPU idempotency store: maps request_id → (execution_id, final ExecutionResponse).
-- Fail-closed invariant: if this table is unreachable, the Gateway blocks all Execute() calls.
-- Retention window: entries expire after the configured retention period (default 24 h).

CREATE TABLE IF NOT EXISTS gpu_idempotency_store (
    request_id   TEXT        NOT NULL,
    execution_id TEXT        NOT NULL,
    state        TEXT        NOT NULL DEFAULT 'QUEUED',
    response     BYTEA,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMP   NOT NULL,

    CONSTRAINT pk_gpu_idempotency        PRIMARY KEY (request_id),
    CONSTRAINT uq_gpu_execution_id       UNIQUE      (execution_id),
    CONSTRAINT chk_gpu_state             CHECK (state IN ('QUEUED','RUNNING','SUCCESS','FAILED','CANCELLED','TIMEOUT'))
);

CREATE INDEX IF NOT EXISTS idx_gpu_idempotency_execution_id ON gpu_idempotency_store (execution_id);
CREATE INDEX IF NOT EXISTS idx_gpu_idempotency_expires_at   ON gpu_idempotency_store (expires_at);
