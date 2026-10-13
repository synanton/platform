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
- `FileSystemContentStore` is the durable local fake for testing without
  a deployed Content Service: same port, same semantics, but payloads
  and sidecars persist under a configured root directory (or a temp dir
  via `createTemp()`), so state survives restarts and outgrows the heap.
  Layout is content-addressed blobs sharded by digest prefix plus JSON
  sidecars (`artifacts/`, `by-digest/`, `refs/`, `retention/`,
  `audit.log`) — inspectable with `cat`, atomic via temp-file moves.
  JDK-only, single-client-process scope.
- This module has **no provider imports** (no JDBC, no Postgres driver,
  no HTTP clients) — `commitix-core` consumers depend on the port only.
