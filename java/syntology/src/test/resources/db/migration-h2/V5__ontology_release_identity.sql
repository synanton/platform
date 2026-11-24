-- Phase 1 (#866): additive immutable release identity and content digest.
ALTER TABLE syntology.ontology_versions
    ADD COLUMN IF NOT EXISTS release_id VARCHAR(64);

ALTER TABLE syntology.ontology_versions
    ADD COLUMN IF NOT EXISTS release_digest VARCHAR(64);
