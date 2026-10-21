-- Removes exactly the rows created by scripts/seed/demo-identity.sql.
-- Idempotent: safe to run more than once. The shared demo organisation is
-- intentionally left in place.
--
-- Usage:
--   psql "$DATABASE_URL" \
--     -v demo_tenant=tenant-a \
--     -v demo_user=alice \
--     -f scripts/seed/demo-identity-teardown.sql

\set ON_ERROR_STOP on

BEGIN;

DELETE FROM topology.acl_grants g
USING topology.users u
WHERE g.subject_id = u.user_id
  AND u.username = :'demo_user'
  AND g.tenant_id = :'demo_tenant'
  AND g.resource_path = '/'
  AND g.source = 'MANUAL';

DELETE FROM topology.users
WHERE username = :'demo_user';

DELETE FROM topology.tenants
WHERE tenant_id = :'demo_tenant';

COMMIT;
