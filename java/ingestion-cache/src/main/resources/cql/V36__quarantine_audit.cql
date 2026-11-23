-- #845: durable lifecycle audit for quarantine generations.
CREATE TABLE IF NOT EXISTS ingestion_cache.quarantine_audit (
  tenant_id             text,
  source_version_id     uuid,
  extraction_generation uuid,
  event_time            timestamp,
  event_id              uuid,
  event_type            text,
  actor                 text,
  reason                text,
  detail                text,
  PRIMARY KEY ((tenant_id, source_version_id), extraction_generation, event_time, event_id)
);
