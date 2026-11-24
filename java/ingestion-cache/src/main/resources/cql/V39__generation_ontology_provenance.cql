-- #866: pin the ontology release used by an extraction request/generation.
ALTER TABLE ingestion_cache.source_versions
    ADD ontology_release_id text;
ALTER TABLE ingestion_cache.source_versions
    ADD ontology_release_digest text;

ALTER TABLE ingestion_cache.quarantine_generations
    ADD ontology_release_id text;
ALTER TABLE ingestion_cache.quarantine_generations
    ADD ontology_release_digest text;

-- Link accepted results to the exact extraction generation (nullable legacy rows).
ALTER TABLE ingestion_cache.extraction_results
    ADD extraction_generation uuid;
