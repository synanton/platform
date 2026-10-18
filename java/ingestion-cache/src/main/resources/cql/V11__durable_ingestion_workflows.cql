-- Tenant discovery is sharded so recovery and outbox workers never enumerate a fixed tenant list.
CREATE TABLE IF NOT EXISTS ingestion_cache.tenant_registry (
  bucket          int,
  tenant_id       text,
  registered_at   timestamp,
  PRIMARY KEY ((bucket), tenant_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.workflow_states (
  tenant_id          text,
  workflow_id        uuid,
  workflow_type      text,
  workflow_version   int,
  operation_id       uuid,
  run_id             uuid,
  source_id          text,
  resource_id        text,
  principal_id       text,
  principal_type     text,
  request_id         text,
  idempotency_key    text,
  state              text,
  current_step       text,
  created_at         timestamp,
  updated_at         timestamp,
  started_at         timestamp,
  completed_at       timestamp,
  correlation_id     text,
  failure_code       text,
  failure_message    text,
  retry_count        int,
  next_attempt_at    timestamp,
  PRIMARY KEY ((tenant_id), workflow_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.workflow_steps (
  tenant_id          text,
  workflow_id        uuid,
  step_id            text,
  command_id         uuid,
  command_type       text,
  expected_events_json text,
  timeout_millis     bigint,
  retry_policy_json  text,
  compensation      text,
  status             text,
  attempt_count      int,
  input_json         text,
  output_json        text,
  started_at         timestamp,
  completed_at       timestamp,
  next_attempt_at    timestamp,
  last_error         text,
  PRIMARY KEY ((tenant_id, workflow_id), step_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.workflow_leases (
  tenant_id         text,
  workflow_id       uuid,
  lease_owner       uuid,
  lease_expires_at  timestamp,
  PRIMARY KEY ((tenant_id, workflow_id))
);

-- SourceVersion and SourceTombstone already co-locate their publication intent.
-- These fields make the existing intent retryable and inspectable.
ALTER TABLE ingestion_cache.source_versions ADD event_published_at timestamp;
ALTER TABLE ingestion_cache.source_versions ADD event_attempt_count int;
ALTER TABLE ingestion_cache.source_versions ADD event_last_error text;
ALTER TABLE ingestion_cache.source_versions ADD event_next_attempt_at timestamp;
ALTER TABLE ingestion_cache.source_versions ADD event_dead_lettered boolean;

ALTER TABLE ingestion_cache.source_tombstones ADD event_published_at timestamp;
ALTER TABLE ingestion_cache.source_tombstones ADD event_attempt_count int;
ALTER TABLE ingestion_cache.source_tombstones ADD event_last_error text;
ALTER TABLE ingestion_cache.source_tombstones ADD event_next_attempt_at timestamp;
ALTER TABLE ingestion_cache.source_tombstones ADD event_dead_lettered boolean;

-- Bring the legacy manifest transition relay up to the same bounded retry visibility.
ALTER TABLE ingestion_cache.manifest_transitions_outbox ADD published_at timestamp;
ALTER TABLE ingestion_cache.manifest_transitions_outbox ADD attempt_count int;
ALTER TABLE ingestion_cache.manifest_transitions_outbox ADD last_error text;
ALTER TABLE ingestion_cache.manifest_transitions_outbox ADD next_attempt_at timestamp;
ALTER TABLE ingestion_cache.manifest_transitions_outbox ADD dead_lettered boolean;

CREATE TABLE IF NOT EXISTS ingestion_cache.dead_letter_events (
  tenant_id          text,
  event_id           uuid,
  event_type         text,
  aggregate_type     text,
  aggregate_id       text,
  payload_json       text,
  created_at         timestamp,
  dead_lettered_at   timestamp,
  attempt_count      int,
  last_error         text,
  PRIMARY KEY ((tenant_id), event_id)
);
