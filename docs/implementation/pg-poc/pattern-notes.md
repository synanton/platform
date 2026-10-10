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
- **[PN-7] A bare call on a functional-interface-returning method is
  fetch-and-drop, not invocation — and javac won't tell you.** Resolution
  of PN-6's instance (2026-09-30, same day): `engine.postLoad();` fetches
  the lambda and discards it; only `engine.postLoad().run()` invokes.
  The bare form is a valid statement expression — the compiler accepts it
  with no warning (opt-in only: `@CheckReturnValue`, ErrorProne
  `ReturnValueIgnored`, SpotBugs `RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT`).
  Blast radius, three legs: PG postLoad (no IVFFlat/ANALYZE — fully
  silent, caught by rerun), YDB truncate (never ran — masked by fresh
  prefixes), YDB close (transport never closed — harmless one-shot, leaks
  long-lived). Retroactive correction: earlier YDB closeouts claiming
  "truncate-on-start verified" should be read as "redundant mechanism,
  unverified" — observed cleanliness came from prefix naming, not the
  never-invoked path (amendment filed with the YDB closeout).
  Grep signature: bare `engine.(truncate|close|postLoad)();` — any
  `Runnable`/`Supplier`/`Callable`-returning call site without a visible
  `.run()`/`.get()`/`.call()` is silently dropped.
  Structural prevention (post-R3 refactor, recorded not executed mid-run):
  audit whether any leg genuinely needs deferral; if not, return void —
  the caller can't drop what isn't returned — or a `PendingAction`
  wrapper with `@CheckReturnValue`. The current `Runnable` shape is the
  worst of both: never deferred in practice, shaped like deferral.
  Detection heuristic: any functional-interface return in harness code
  gets inspected; this class never fails loudly on any JVM without
  opt-in static analysis.
- **[PN-8] A guard whose only caller is its own unit test protects
  nothing.** Origin: 028e (2026-09-30): `RetrievabilityGuard.check()` had
  exactly one caller repo-wide (its test); a 120-empty artifact flowed
  through emission unchecked on every path. Sibling finding, same audit:
  `EligibilityValidator.validate()` — also test-only. (Q3Emitter references
  the guard in javadoc only, which reads as wiring and isn't.) Rule: every
  Guard/Validator/Check must name a production-path caller; audit by
  grepping the class name outside `test/` and self. Other zeros found in
  the same pass (`ExtractionRequestValidator`, `JsonResponseValidator`,
  `BudgetGuard`, `AdapterResidencyGuard`) belong to owning workstreams —
  flagged, not claimed. Wiring both bench guards into RunLeg post-emission
   is a post-R3 commit (all legs, shared layer).
- **[PN-9] Composition is roles, not positions — and validation fails loud.**
  Origin: VEC-B2.1/B2.2. Providers resolve by capability role
  (`metadata.*` needs document storage, `vector.*` needs vector search);
  `validateComposition` names provider + capability + reason on mismatch.
  Silent fallback to a default engine is a correctness bug class, not a
  convenience — every cross-provider code path must cite its composition.
- **[PN-10] A blank evidence cell is a lie — mark unmeasured, don't imply.**
  Origin: VEC-B6.1. The selection matrix carries functional columns where
  most cells have no runs; an empty cell reads as "zero/unknown-later" and
  downstream decisions treat it as data. Rule: every cell has a measured
  number with vintage or an explicit `unmeasured` flag with the reason
  (no embeddings, no hybrid runs, unpublished vendor figure). Applies to any
  future comparison table, not just engines.
- **[PN-11] Score spaces don't transfer — filter in the portable layer.**
  Origin: VEC-B1/B3 (Lucene TF-IDF vs HNSW cosine distances). A threshold
  meaningful in one engine is meaningless in another; pushing it into the
  engine query bakes in non-portable semantics (and sentinel values may not
  round-trip). Rule: retrieval is topK-bounded, thresholding happens
  caller-side in Java (`MIN_SCORE_NO_THRESHOLD` contract).
- **[PN-12] Attribution needs identical inputs, not similar ones.**
  Origin: R3 Cassandra-vs-Lucene parity runs (VEC-B5). Two legs are
  comparable only on byte-identical queries against a canonical corpus hash;
  the runner refuses mismatched hashes rather than running anyway. A
  benchmark that "runs anyway" on drifted inputs produces numbers that read
  as comparable and aren't.
- **[PN-13] Runbooks are validated by execution, not review.**
  Origin: DOC-D1.1 (2026-10-09): fresh-eyes pass in a clean env caught a
  live defect (fake corpus hash correctly refused) that review had passed
  over twice. A runbook no one has executed verbatim from zero state is a
  draft regardless of review stamps. Same standard applies to any
  operator-facing procedure doc.
