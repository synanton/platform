# Cross-backend pattern notes

Seed of the pattern library: one line per constraint that surfaced on one
backend and constrains the others. Lifecycle discipline lives in
`../ydb-poc/dev-guide-tests.md` (generalized section + PG quota shapes);
this file holds schema/adapter-shape findings. Each entry names its origin
ticket — a future backend checks this file before writing its first DDL.

House rules: **append-only, chronological** — new findings go at the end,
order preserved, history never edited (corrections are new entries citing
the old). Entries carry stable labels (`PN-1`…); tickets cite the label
(e.g. "pattern-notes.md PN-1"), never a line number or heading anchor.

- **[PN-1] Domain ids are opaque strings — never force them into `uuid`.**
  Origin: PG-POC-004. `tenant_a`, `d1` cannot round-trip through Postgres's
  `uuid` type (strict format); identity columns are `text`. YDB is immune
  (Utf8 throughout) — this is PG-specific, but any future SQL backend with a
  native uuid type rediscovers it unless the line above stops them.
- **[PN-2] Parameterize by default, on every backend.** Origin: YDB-041 (perf:
  250× on literals) → PG-POC-004 (correctness/security: identical policy,
  different motive). The dev-guide rule transferred cleanly; the motive
  column is per-backend, the rule is not.
- **[PN-3] OCC mechanisms differ, semantics must not.** Origin: PG-POC-004. PG
  serializes writers with `SELECT ... FOR UPDATE` (row locks); YDB uses
  serializable-RW transactions (distributed abort). Both map to the same
  contract (`expectRevision` → CONFLICT → retry-after-reread, pinned by the
  shared suite + `RevisionAtomicityTest`). If either implementation changes
  its concurrency mechanism, re-run the racer test first — semantic drift
  between backends is a correctness bug, not a style choice.
- **[PN-4] Plan-shape guards catch what result-shape tests miss.** Origin:
  PG-POC-007-6 (fourth instance in this PoC family: Map.of, vector
  text-duplication, and the vector leg's missing filter push-down — none
  visible to outcome-only tests). A guard that verifies mechanism
  (predicate in plan, topology recorded) discovers defects the retrieval
  tests pass over. Every retrieval leg gets both shapes: result
  correctness AND plan-shape evidence.
- **[PN-5] Tenant-less port methods need DEFINER functions — and the
  function's argument validation is the security boundary.** Origin:
  PG-POC-007-7a. `delete()`/`rebuild()` carry no tenant scope (YDB scans
  its local indexes for the same reason); PG resolves keys through narrow
  SECURITY DEFINER functions owned by a BYPASSRLS-capable role. Narrow by
  shape (exact chunk+generation; global flip), never open-ended: any caller
  reaching the function with arbitrary arguments reads/writes any tenant.
  Production must audit role scope + argument handling. Tracked:
  `007-followup-definer-audit.md`.
- **[PN-6] Mitigation ≠ mechanism — and the record must say which closed
  the class.** Origin: 028e run-1 (2026-09-30): a full leg completed with
  no IVFFlat and no error; root cause for that silent instance remains
  unexplained. The class is closed by verify-or-throw (post-load prints +
  `pg_indexes` check), which prevents recurrence without explaining the
  original. "Closed by mitigation" must never read as "root cause found"
  six months later — write which one it is.
- **[PN-7] A bare call on a Runnable-returning accessor is fetch-and-drop,
  not invocation — and javac won't tell you.** Resolution of PN-6's
  instance (2026-09-30, same day): `engine.postLoad();` fetches the lambda
  and discards it; only `engine.postLoad().run()` invokes. Same for
  `truncate()`/`close()`. Compiles silent, no warning, all downstream
  evidence consistent with "the step ran" (load succeeded, queries
  returned, DONE written) while the step never executed. Supplier-returning
  accessors don't have this shape (`.get()` is always written); Runnables
  do. Review rule: every call site on a `Runnable`-returning accessor must
  show `.run()` — grep `engine\.(truncate|close|postLoad)();` finds the
  bug class repository-wide.
