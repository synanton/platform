-- H2-compatible schema used for unit tests.
-- Mirrors production schema (db/migration/V1__init.sql) but replaces
-- PostgreSQL-specific constructs with H2 equivalents.

CREATE SCHEMA IF NOT EXISTS topology;

CREATE TABLE topology.organizations (
    org_id     UUID NOT NULL DEFAULT RANDOM_UUID(),
    name       TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_organizations PRIMARY KEY (org_id)
);

CREATE TABLE topology.users (
    user_id    UUID NOT NULL DEFAULT RANDOM_UUID(),
    org_id     UUID NOT NULL REFERENCES topology.organizations(org_id),
    username   TEXT NOT NULL UNIQUE,
    uid        INT  NOT NULL,
    gids       ARRAY NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_users PRIMARY KEY (user_id)
);

CREATE TABLE topology.acl_grants (
    grant_id      UUID NOT NULL DEFAULT RANDOM_UUID(),
    org_id        UUID NOT NULL REFERENCES topology.organizations(org_id),
    subject_id    UUID NOT NULL,
    subject_type  TEXT NOT NULL,
    resource_path TEXT NOT NULL,
    permission    TEXT NOT NULL,
    source        TEXT NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_acl_grants PRIMARY KEY (grant_id)
);

CREATE INDEX acl_grants_subject ON topology.acl_grants (subject_id, resource_path);
CREATE INDEX acl_grants_resource ON topology.acl_grants (resource_path);

INSERT INTO topology.organizations (org_id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Org');
