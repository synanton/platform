-- RAKG PR #3: document-relative provenance for extracted mentions.
-- Chunk-relative offsets remain the adapter contract; these columns carry the
-- parsed-document span where the chunker knows it (-1 otherwise).
ALTER TABLE ingestion_cache.chunks_payload ADD document_offset_start int;
ALTER TABLE ingestion_cache.chunks_payload ADD document_offset_end int;
