-- Development demo identity seed.
--
-- Idempotent: safe to run more than once. The scripts are the source of truth
-- for demo identity rows; application code does not create them implicitly.
--
-- Usage:
--   psql "$DATABASE_URL" \
--     -v demo_tenant=tenant-a \
--     -v demo_user=alice \
--     -v demo_uid=1001 \
--     -v "demo_gids={1001,2000}" \
--     -f scripts/seed/demo-identity.sql
--
-- `demo_gids` is a Postgres array literal (e.g. '{1001,2000}').
--
-- Identity 1.29 (#341) adds the set-shaped authorized_tenant_ids contract.
-- This script seeds the current topology identity tables (org, tenant, user,
-- ACL membership). When #341 lands, extend this script with the singleton
-- authorized_tenant_ids row for :demo_tenant; do not create that table here.
--
-- #341 will add:
--   INSERT INTO identity.authorized_tenant_ids (subject, tenant_id)
--   VALUES (:'demo_user', :'demo_tenant')
--   ON CONFLICT DO NOTHING;
-- Replace the schema/column names with the contract #341 actually lands.

\set ON_ERROR_STOP on

BEGIN;

INSERT INTO topology.organizations (org_id, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Org')
ON CONFLICT (org_id) DO NOTHING;

INSERT INTO topology.tenants (tenant_id, display_name)
VALUES (:'demo_tenant', :'demo_tenant')
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO topology.users (user_id, org_id, username, uid, gids)
VALUES (
    gen_random_uuid(),
    '00000000-0000-0000-0000-000000000001',
    :'demo_user',
    :demo_uid,
    :'demo_gids'::int[]
)
ON CONFLICT (username) DO NOTHING;

-- Tenant membership represented as a root READ grant. Idempotent via the
-- NOT EXISTS guard because acl_grants has no natural unique key.
INSERT INTO topology.acl_grants (
    org_id, subject_id, subject_type, resource_path, permission, source, tenant_id
)
SELECT
    '00000000-0000-0000-0000-000000000001',
    u.user_id,
    'USER',
    '/',
    'READ',
    'MANUAL',
    :'demo_tenant'
FROM topology.users u
WHERE u.username = :'demo_user'
  AND NOT EXISTS (
      SELECT 1
      FROM topology.acl_grants g
      WHERE g.subject_id = u.user_id
        AND g.resource_path = '/'
        AND g.tenant_id = :'demo_tenant'
  );

COMMIT;
