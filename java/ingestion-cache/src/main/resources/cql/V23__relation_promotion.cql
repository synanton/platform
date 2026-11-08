-- RAKG PR #4a: durable canonical-relation promotion decisions.
CREATE TABLE IF NOT EXISTS ingestion_cache.relation_promotion_results (
  tenant_id             text,
  source_version_id     uuid,
  relation_id           text,
  subject_mention_id    text,
  object_mention_id     text,
  predicate             text,
  promotion_status      text,
  canonical_subject_id  text,
  canonical_object_id   text,
  reason                text,
  evidence_json         text,
  strategy_version      text,
  ontology_version      text,
  decided_at            timestamp,
  superseded_at         timestamp,
  PRIMARY KEY ((tenant_id, source_version_id), relation_id)
);
