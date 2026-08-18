-- H2-compatible multi-tenant migration (test only)
-- ontology_versions already has tenant_id from V1; entity_types/relation_types not present in schema.
-- Indexes below are no-ops when tables don't exist - wrapped as pure metadata comments for H2.
-- This migration intentionally left as a compatibility stub for H2 test environments.
SELECT 1;
