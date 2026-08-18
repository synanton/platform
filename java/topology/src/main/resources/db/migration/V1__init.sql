CREATE SCHEMA IF NOT EXISTS topology;

CREATE TABLE topology.organizations (
    org_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE topology.users (
    user_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID NOT NULL REFERENCES topology.organizations(org_id),
    username   TEXT NOT NULL UNIQUE,
    uid        INT  NOT NULL,
    gids       INT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE topology.acl_grants (
    grant_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL REFERENCES topology.organizations(org_id),
    subject_id    UUID NOT NULL,
    subject_type  TEXT NOT NULL CHECK (subject_type IN ('USER', 'GROUP')),
    resource_path TEXT NOT NULL,
    permission    TEXT NOT NULL CHECK (permission IN ('READ', 'WRITE', 'ADMIN')),
    source        TEXT NOT NULL CHECK (source IN ('FS_BOOTSTRAP', 'MANUAL')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX acl_grants_subject ON topology.acl_grants (subject_id, resource_path);
CREATE INDEX acl_grants_resource ON topology.acl_grants (resource_path);

-- Seed the single demo organisation
INSERT INTO topology.organizations (org_id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Org');
