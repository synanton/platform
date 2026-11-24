-- #870: repair migration. V1 never created entity_types/relation_types, but V2
-- alters them. Existing installations may already have them (manual/legacy
-- bootstrap); this migration is idempotent and safe to apply out of order.
CREATE TABLE IF NOT EXISTS syntology.entity_types (
    version TEXT
);

ALTER TABLE syntology.entity_types
    ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'demo';

CREATE INDEX IF NOT EXISTS idx_entity_types_tenant_version
    ON syntology.entity_types (tenant_id, version);

CREATE TABLE IF NOT EXISTS syntology.relation_types (
    version TEXT
);

ALTER TABLE syntology.relation_types
    ADD COLUMN IF NOT EXISTS tenant_id TEXT NOT NULL DEFAULT 'demo';

CREATE INDEX IF NOT EXISTS idx_relation_types_tenant_version
    ON syntology.relation_types (tenant_id, version);
