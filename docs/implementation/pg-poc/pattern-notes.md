# Cross-backend pattern notes

Seed of the pattern library: one line per constraint that surfaced on one
backend and constrains the others. Lifecycle discipline lives in
`../ydb-poc/dev-guide-tests.md` (generalized section + PG quota shapes);
this file holds schema/adapter-shape findings. Each entry names its origin
ticket — a future backend checks this file before writing its first DDL.

- **Domain ids are opaque strings — never force them into `uuid`.**
  Origin: PG-POC-004. `tenant_a`, `d1` cannot round-trip through Postgres's
  `uuid` type (strict format); identity columns are `text`. YDB is immune
  (Utf8 throughout) — this is PG-specific, but any future SQL backend with a
  native uuid type rediscovers it unless the line above stops them.
- **Parameterize by default, on every backend.** Origin: YDB-041 (perf:
  250× on literals) → PG-POC-004 (correctness/security: identical policy,
  different motive). The dev-guide rule transferred cleanly; the motive
  column is per-backend, the rule is not.
- **OCC mechanisms differ, semantics must not.** Origin: PG-POC-004. PG
  serializes writers with `SELECT ... FOR UPDATE` (row locks); YDB uses
  serializable-RW transactions (distributed abort). Both map to the same
  contract (`expectRevision` → CONFLICT → retry-after-reread, pinned by the
  shared suite + `RevisionAtomicityTest`). If either implementation changes
  its concurrency mechanism, re-run the racer test first — semantic drift
  between backends is a correctness bug, not a style choice.
