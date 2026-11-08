-- RAKG Phase 2 (PR #2): extraction status + preview output store.
--
-- extraction_status on the source_versions row tracks the async consumer:
-- PENDING -> EXTRACTING -> KNOWLEDGE_READY | EXTRACTION_FAILED.
-- extraction_results is the pre-Relix stub: one row per chunk with the adapter's
-- PreEntity / RelationCandidate JSON. It is overwritten per content ref/ordinal
-- and is not the graph commit path (SHACL/Relix is a later phase).
ALTER TABLE ingestion_cache.source_versions ADD extraction_status text;

CREATE TABLE IF NOT EXISTS ingestion_cache.extraction_results (
  tenant_id         text,
  content_ref_id    uuid,
  chunk_ordinal     int,
  source_version_id uuid,
  entities_json     text,
  relations_json    text,
  extracted_at      timestamp,
  PRIMARY KEY ((tenant_id, content_ref_id), chunk_ordinal)
);
