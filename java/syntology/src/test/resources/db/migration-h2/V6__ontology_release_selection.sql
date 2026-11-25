-- A3 (#899): per-ontology release identity and active selection.
-- Existing rows are the Supply Chain demo ontology; adding IT Books must not
-- deprecate or globally replace that active release.
ALTER TABLE syntology.ontology_versions
    ADD COLUMN IF NOT EXISTS ontology_id VARCHAR(64);

UPDATE syntology.ontology_versions
    SET ontology_id = 'supply-chain'
    WHERE ontology_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_ontology_versions_tenant_ontology_status
    ON syntology.ontology_versions (tenant_id, ontology_id, status);
