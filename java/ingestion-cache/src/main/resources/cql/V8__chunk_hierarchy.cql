-- Hierarchical chunking (retrieval benchmark B2, T05): each chunk records the document section
-- it belongs to (DocumentStructureBuilder id, unique per document), that section's parent, and
-- its heading level, so retrieval can return a hit's whole section (small-to-big).
-- Existing rows read back as empty / 0.
ALTER TABLE ingestion_cache.chunks_payload ADD section_id text;
ALTER TABLE ingestion_cache.chunks_payload ADD parent_section_id text;
ALTER TABLE ingestion_cache.chunks_payload ADD heading_level int;
