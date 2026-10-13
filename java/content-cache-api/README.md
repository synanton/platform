# content-cache-api

Commitix port-types home for optional external payload storage
(proposal §4.1: Content Cache / Commitix integration, v3 approved).

- `org.synanton.contentcache` holds the port surface: `ContentStore`
  (put/get/exists, reference register/release, legal hold, retention),
  `ContentReference` (opaque v1 reference: parse/encode, 512-char bound),
  `ContentMetadata`, `PutResult`, and the exception hierarchy
  (`ContentStoreException`, `ContentNotFoundException` for
  indistinguishable cross-tenant not-found, `ContentIntegrityException`).
- `InMemoryContentStore` is the test/local fake: per-tenant dedup by
  digest, tenant-scoped reads, digest verification on read,
  owner-scoped reference release. Mirrors the Postgres semantics under
  test in `content-cache-postgres`.
- This module has **no provider imports** (no JDBC, no Postgres driver,
  no HTTP clients) — `commitix-core` consumers depend on the port only.
