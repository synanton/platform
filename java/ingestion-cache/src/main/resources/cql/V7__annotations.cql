-- v1.24/1.25 AAP-1: high-volume annotation instances attached to chunks/targets.
-- Governance-weight objects (definitions, versions, dependency DAG, processing runs)
-- live in the `annotations` Postgres service; this table holds the per-target
-- annotation values themselves (design §7, §31).

CREATE TABLE IF NOT EXISTS ingestion_cache.annotations (
  tenant_id              text,
  target_type            text,
  target_id              text,
  annotation_id          uuid,
  definition_id          text,
  definition_version     int,
  annotation_type        text,
  namespace              text,
  name                   text,
  value                  text,
  producer               text,
  producer_version       text,
  confidence             double,
  source_classification  set<text>,
  representation_used    text,
  provenance             text,
  processing_run_id      uuid,
  created_at             timestamp,
  invalidated_at         timestamp,
  PRIMARY KEY ((tenant_id, target_type, target_id), annotation_id)
);
