-- Add tenant_id to version and entity tables
ALTER TABLE syntology.ontology_versions
    ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'demo';

ALTER TABLE syntology.entity_types
    ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'demo';

ALTER TABLE syntology.relation_types
    ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'demo';

-- Unique constraint on (tenant_id, version)
CREATE UNIQUE INDEX IF NOT EXISTS uq_ontology_versions_tenant_version
    ON syntology.ontology_versions (tenant_id, version);

CREATE INDEX IF NOT EXISTS idx_entity_types_tenant
    ON syntology.entity_types (tenant_id, version);

CREATE INDEX IF NOT EXISTS idx_relation_types_tenant
    ON syntology.relation_types (tenant_id, version);
