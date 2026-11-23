-- #845 Phase 2a: durable audit for the available hard-deletion scope.
-- Does not cover cache rows or capture/diagnostic artifacts (Phase 2b).
CREATE TABLE IF NOT EXISTS ingestion_cache.compliance_deletion_audit (
  tenant_id         text,
  source_version_id uuid,
  event_time        timestamp,
  event_id          uuid,
  event_type        text,
  record_scope      text,
  deleted_count     int,
  actor             text,
  reason            text,
  detail            text,
  PRIMARY KEY ((tenant_id, source_version_id), event_time, event_id)
);
