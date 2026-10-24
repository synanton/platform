# content-cache-postgres

JDBC `ContentStore` adapter against the canonical content-cache schema
(same DDL as the Go reference service `migrations/*.up.sql`).

- `TenantScopedContentStore` is the public entry point. Scope is a required
  constructor value (`TenantScope`), never a ThreadLocal/request lookup; a
  foreign `ContentReference` is not-found/absent. `JdbcContentStore` is
  package-private so direct use outside this package is a compile error.
- The underlying repository implements `org.synanton.contentcache.ContentStore`:
  put with `(tenant_id, digest)` dedup, tenant-scoped reads with digest
  verification, reference register/release, audited legal-hold changes,
  retention updates. All writes that span tables commit atomically.
- `ContentCacheSchema.ensureSchema` applies the canonical
  `schema.sql`; tests and operators call here, never inline copies.
- Tenant isolation is enforced twice: every operation runs in a transaction
  with `set_config('app.tenant_id', ?, true)`, and `artifacts`,
  `retention_policies`, `reference_registry`, and `audit_log` use `FORCE ROW
  LEVEL SECURITY`. A raw query without the transaction-local scope sees zero
  rows, never all rows.
- Tests follow the platform live-Postgres policy: one
  `postgres:16-alpine` container per JVM (`PostgresTestBase`),
  per-test tenant ids for isolation, least-privilege `app` role so
  every assertion exercises the policy path, visible-skip when the
  container is unavailable.

## Coverage boundary

| Layer | Covered by this module? | Control |
|---|---|---|
| Relational content cache (`artifacts`, `retention_policies`, `reference_registry`, `audit_log`) | Yes | `TenantScopedContentStore` + transaction-local `app.tenant_id` + RLS |
| Search projection | No | Separate per-tenant Lucene index/snapshot; re-verified by the two-tenant acceptance |
| Provenance reads | No | Separate tenant-scoped cache reads; B → 404, never A |

The facade is not a global tenant gate. Do not infer search or provenance
isolation from its tests; those layers have their own negative tests.

**Duplicate semantics:** dedup is per `(tenant_id, digest)`. Identical bytes in
two tenants are deliberately stored as two artifacts. Sharing an artifact
across tenants would make existence/timing probing possible; do not add
cross-tenant digest dedup without changing the isolation contract.
