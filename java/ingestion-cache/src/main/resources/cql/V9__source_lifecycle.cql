-- A configured source may discover multiple resource identities. Source history
-- is keyed by logical identity, never by content_ref_id.
CREATE TABLE IF NOT EXISTS ingestion_cache.sources (
  tenant_id                 text,
  source_id                 text,
  connector_id              text,
  connector_type            text,
  source_endpoint           text,
  capability_flags          set<text>,
  status                    text,
  created_at                timestamp,
  last_successful_sync_at   timestamp,
  PRIMARY KEY ((tenant_id), source_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.source_identities (
  tenant_id                 text,
  source_id                 text,
  connector_id               text,
  resource_id                text,
  PRIMARY KEY ((tenant_id, source_id), connector_id, resource_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.source_versions (
  tenant_id          text,
  connector_id       text,
  source_id          text,
  resource_id        text,
  version_id         uuid,
  content_digest     text,
  source_revision    text,
  observed_at        timestamp,
  content_ref_id     uuid,
  incarnation_id     uuid,
  event_id           uuid,
  event_type         text,
  event_payload_json text,
  event_published    boolean,
  PRIMARY KEY ((tenant_id, connector_id, source_id, resource_id), version_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.source_tombstones (
  tenant_id                 text,
  connector_id              text,
  source_id                 text,
  resource_id               text,
  deleted_at                timestamp,
  tombstone_id              uuid,
  deleted_by_principal_id   text,
  deleted_by_principal_type text,
  version_count             int,
  versions_manifest_ref     uuid,
  next_incarnation_id       uuid,
  event_id                  uuid,
  event_type                text,
  event_payload_json        text,
  event_published           boolean,
  PRIMARY KEY ((tenant_id, connector_id, source_id, resource_id), tombstone_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.source_version_manifest_entries (
  tenant_id       text,
  manifest_ref    uuid,
  version_id      uuid,
  PRIMARY KEY ((tenant_id, manifest_ref), version_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.ingestion_runs (
  tenant_id       text,
  run_id          uuid,
  operation_id    uuid,
  source_id       text,
  status          text,
  started_at      timestamp,
  completed_at    timestamp,
  discovered      int,
  changed         int,
  unchanged       int,
  deleted         int,
  failed          int,
  skipped         int,
  quarantined     int,
  cancelled       int,
  error_ref       text,
  PRIMARY KEY ((tenant_id), run_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.ingestion_manifests (
  tenant_id       text,
  run_id          uuid,
  manifest_id     uuid,
  generated_at    timestamp,
  entry_count     int,
  entries_ref     text,
  PRIMARY KEY ((tenant_id), run_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.ingestion_errors (
  tenant_id       text,
  error_id        uuid,
  type            text,
  title           text,
  status          int,
  detail          text,
  instance        text,
  code            text,
  retryable       boolean,
  request_id      text,
  details_json    text,
  occurred_at     timestamp,
  PRIMARY KEY ((tenant_id), error_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.ingestion_manifest_entries (
  tenant_id          text,
  run_id             uuid,
  connector_id       text,
  source_id          text,
  resource_id        text,
  result             text,
  source_version_id  uuid,
  error_ref          text,
  observed_at        timestamp,
  PRIMARY KEY ((tenant_id, run_id), connector_id, source_id, resource_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.operations (
  tenant_id                   text,
  operation_id                uuid,
  operation_type              text,
  status                      text,
  requested_by_principal_id   text,
  created_at                  timestamp,
  started_at                  timestamp,
  completed_at                timestamp,
  progress_completed          int,
  progress_total              int,
  outcome                     text,
  error_id                    uuid,
  request_id                  text,
  idempotency_key             text,
  PRIMARY KEY ((tenant_id), operation_id)
);

CREATE TABLE IF NOT EXISTS ingestion_cache.operation_idempotency (
  tenant_id        text,
  principal_id     text,
  endpoint         text,
  idempotency_key  text,
  operation_id     uuid,
  created_at       timestamp,
  PRIMARY KEY ((tenant_id, principal_id), endpoint, idempotency_key)
);
