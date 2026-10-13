# content-cache-postgres

JDBC `ContentStore` adapter against the canonical content-cache schema
(same DDL as the Go reference service `migrations/*.up.sql`).

- `JdbcContentStore` implements `org.synanton.contentcache.ContentStore`:
  put with `(tenant_id, digest)` dedup, tenant-scoped reads with digest
  verification, reference register/release, audited legal-hold changes,
  retention updates. All writes that span tables commit atomically.
- `ContentCacheSchema.ensureSchema` applies the canonical
  `schema.sql`; tests and operators call here, never inline copies.
- Tests follow the platform live-Postgres policy: one
  `postgres:16-alpine` container per JVM (`PostgresTestBase`),
  per-test tenant ids for isolation, least-privilege `app` role so
  every assertion exercises the policy path, visible-skip when the
  container is unavailable.
