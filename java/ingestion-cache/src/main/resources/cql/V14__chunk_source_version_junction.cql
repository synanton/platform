-- Provenance race fix (#251 review): the application-side union on
-- chunks_payload.source_version_ids was a read-modify-write — two workers
-- processing identical bytes under different source versions could lose an
-- attribution. Attribution now lives in a junction table written with plain
-- idempotent inserts, keyed for reverse lookup by source version (which #255's
-- search eligibility filter reads when checking tombstone state).
CREATE TABLE IF NOT EXISTS ingestion_cache.chunk_source_versions (
  tenant_id         text,
  source_version_id uuid,
  content_ref_id    uuid,
  chunk_ordinal     int,
  chunk_sha256      text,
  processing_run_id uuid,
  PRIMARY KEY ((tenant_id, source_version_id), content_ref_id, chunk_ordinal)
);

-- Backfill: none. Pre-#251 chunk rows carry no version provenance, and slice
-- data is disposable — provenance re-derives on reprocessing.
