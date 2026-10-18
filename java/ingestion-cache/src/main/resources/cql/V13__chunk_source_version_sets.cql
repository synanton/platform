-- Provenance refinement (#251 review): a chunk is a content-addressed knowledge
-- unit — identical bytes delivered under two source versions derive the same
-- chunks. The chunk attributes itself to every source version that observed it
-- (many-to-many), so each version's provenance path resolves without forking the
-- deduped artifact.
ALTER TABLE ingestion_cache.chunks_payload ADD source_version_ids set<uuid>;
ALTER TABLE ingestion_cache.chunks_payload DROP source_version_id;

-- The processing run records every source version whose bytes it processed.
ALTER TABLE ingestion_cache.knowledge_processing_runs ADD input_source_version_ids set<uuid>;
