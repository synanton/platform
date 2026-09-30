# PG-POC Ticket Plan — Phase 0 through Phase 6

Branch: `DESIGN-PostgreSQL` (created and checked out; rebased to main incl. YDB PR #51)
Governing proposal: Design Proposal Evaluate PostgreSQL as a Third Synvault/Synquest Persistence Backend, Rev 4
Governing architecture: Platform Architecture 1.0 (approved)
Inherited from: YDB PoC (PR #51, merged)
Ticket count: 18 (000–017)
Legend: 🔴 Blocker · 🟠 Must-hold · 🔵 PoC execution · 🟡 Production gate

## §0. Inherited Rules (from YDB PoC closeout)

These rules are already in main and apply to PG from day one. No PG ticket is required to introduce them; every PG ticket must satisfy them.

| Rule | Origin | Where enforced |
|---|---|---|
| Green tests state their scope | Eligibility-scope review | Test naming, conformance registry scope(), tracker gate lines |
| Conformance claims gated by evidence | §9.3 conformance principle | ProviderRegistry, capability flags |
| No metric without its topology | 028 benchmark review | Metric name suffixes, pre-flight annotations |
| No capability claim without its qualifier | Eligibility review | Tracker, conformance registry |
| Same throwaway scope until §14–15 resolves | Architecture 1.0 | Provisional file, PR draft state |
| Teardown WARN rule | Path-accumulation closeout | Dev-guide; PG-POC-012 verifies |
| DDL-in-one-place rule | SchemaInstaller split closeout | Per-backend schema module; PG-POC-003 verifies |
| Lifecycle rule | Path-accumulation closeout | Test teardown; PG-POC-012 verifies |
| Visible-skip rule | Off-container guard closeout | Skip annotations; PG-POC-012 verifies |

Any PG ticket that introduces a new infrastructure component must state which of the above rules it inherits and how it's verified. This is the analogue of the YDB proposal's "capability claims require conformance evidence" — a discipline rule, not a ticket.

## §1. Phased Sequencing

```text
Phase 0A — Setup (no PG code)           001 002
Phase 0B — Infrastructure inheritance   000
Phase 0C — Schema + RLS + contract      003 012 013
Phase 0D — Must-holds                   005 006 (pre-gates; 013 is Gate B input)
Phase 0E — Benchmark comparator         017 (blocks the 006 convergence run)
───── Pre-Phase-2 gates ─────
    Gate A (RLS × ANN)                  005
    Gate B (comparator validity)        006 (incl. 013 input)
Phase 1 — PostgresSynvaultStore         004 (execution phase)
Phase 2 — PostgresSynquestEngine        007 008
Phase 3 — Projection consistency        009
Phase 4 — Scale, failure, cost          010
Phase 5 — Migration tooling             011 014
Phase 6 — Decision contribution         015 016
```

Phase 2 does not begin until Gate A and Gate B both pass. No PG Phase 2 engine code, no benchmark, no scale/freshness run before then. Same discipline as YDB's §0.1.

## §2. Critical Path

```text
001 (pin versions)
  → 002 (stability inventory)
    → 000 (inherit YDB infra)
      → 003 (schema + RLS)
        → 005 (Gate A: RLS × ANN) ──┐
        → 006 (Gate B: comparator)  ├──→ Phase 2 gates open
        → 013 (tie-break) ──────────┘
          → 004 (SynvaultStore)
          → 007 (SynquestEngine)
            → 008 (eligibility/side-channel/temporal)
              → 009 (projection consistency)
                → 010 (scale/failure/cost)
                  → 014 (PG 4-leg run)
                    → 015 (Phase 6 contribution)
                      → 016 (joint Phase 6 with YDB)
```

Long pole: 001 → 000 → 003 → 005/006 → 007 → 009 → 014 → 016.

External gates:

- Baseline service (inherited from YDB PoC; needed for 006 and 014).
- Joint Phase 6 with YDB (per proposal §0.2).

## §3. Phase 0A — Setup

🔴 PG-POC-001 — Pin Postgres + pgvector + BM25 extension versions

- Depends on: —
- Description: Pin exact versions: Postgres (major.minor.patch), pgvector (extension version), BM25 extension (choice + version — e.g., pg_search/ParadeDB, bm25s, or tsvector-with-GIN as the fallback). Record in a version manifest.
- Acceptance: Version manifest committed; all three specified to patch level; BM25 extension choice justified (GA/Preview status recorded).
- Proposal: §10 Phase 0, §13 risk (BM25 maturity).
- Inherits: YDB PoC's 001-version-manifest.md structure.

🔴 PG-POC-002 — Extension feature stability inventory

- Depends on: PG-POC-001
- Description: For each extension used: GA/Preview/Beta/Experimental status, known limitations, upgrade compatibility, production-readiness implications. pgvector is GA; BM25 extensions vary. Re-validation trigger for version changes.
- Acceptance: Feature-by-feature table committed; Preview/Beta dependencies flagged as production risk; re-validation procedure documented.
- Proposal: §10 Phase 0.
- Inherits: YDB's 003 stability inventory structure.

## §4. Phase 0B — Infrastructure Inheritance

🔴 PG-POC-000 — Inherit YDB infrastructure as prerequisites

- Depends on: —
- Description: Verify the YDB PoC's infrastructure is available and extend where needed:
  - 037 shared port-types home (storage-contract) — reused as-is, no changes.
  - 038 observability contract — reused; PG adapter provides ActiveProviders entries identically.
  - 039 provider selection + startup validation — extended to register PG as a candidate. Startup validation must reject PG configurations that don't satisfy the deployment requirements (same shape as Cassandra-revision fail-fast).
  - 012 capability-boundary ArchUnit rule — extended to include a "no SQL outside synvault-postgres / synquest-postgres" rule, analogous to the CQL/YQL rules.
  - 013–020 must-holds — inherited contract tests run against PG automatically once the adapter exists.
  - 040 call-site rewire — independent; PG does not affect it.
- Acceptance:
  - PG registered in provider selection; startup validation green.
  - ArchUnit rule extended and enforced in CI.
  - Contract test suite runs against PG once the adapter exists.
  - No changes to storage-contract, storage-testkit, or port interfaces.
- Proposal: §7.1, §7.2, §7.3, §12 Architecture.
- Inherits: YDB tickets 037, 038, 039, 012, 013–020.

🟢 PG-POC-012 — Lifecycle / quota discipline from day one

- Depends on: PG-POC-003
- Description: Apply the YDB closeout lifecycle discipline before the first test run:
  - Per-backend quota policy (PG equivalent: connection pool limits, temp table count, WAL/lock resource — identify what's quota-shaped for PG).
  - Synchronous teardown (no best-effort catches; teardown failures log at WARN, never swallow).
  - SchemaInventoryTest analogue (assert expected tables exist, verify against pg_tables).
  - DDL-in-one-place: SQL DDL lives in exactly one module per backend; tests call it, never duplicate.
  - Visible-skip rule: guards that skip off-container skip visibly.
- Acceptance: All five sub-rules verified by test; dev-guide updated for PG-specific quota shapes; the lifecycle section generalized in the dev-guide is applied.
- Evidence: `LifecycleDisciplineTest` 4/4 (synchronous TRUNCATE,
  WARN-helper stderr pin, skip-helper abort-with-reason, DDL source scan),
  `PgQuotaGuardTest` 2/2 (backend connections < 20, no stale
  idle-in-transaction), inventory extended with `text`-identity pin
  (PG-POC-004 finding locked in), dev-guide gains "Postgres quota shapes".
  `PostgresTestBase` exposes `warnTeardown`/`abortVisible` helpers so the
  rules are unit-pinned, not eyeballed. Full module 30/30 green.
- Proposal: §18 PG-POC-012, §19 nine-rule discipline.
- Inherits: YDB closeout (SchemaInstaller, path-accumulation, teardown WARN).

## §5. Phase 0C — Schema + Tie-break

🔴 PG-POC-003 — Schema deployment + RLS policies

- Depends on: PG-POC-001, PG-POC-002
- Description: Deploy the §8.1 schema. Apply RLS policies (tenant_isolation) to documents, chunks, provenance, publication_log. Schema DDL lives in exactly one place (synvault-postgres/schema.sql or equivalent); tests call it.
- Acceptance:
  - Schema deploys to a fresh container.
  - SchemaInventoryTest green (all four tables + indexes present).
  - RLS enabled on all four tables; app.tenant_id set at session start.
  - No inline SQL DDL in test code.
- Proposal: §8.1, §8.2, §18 PG-POC-003.
- Inherits: DDL-in-one-place rule.

🔴 PG-POC-013 — Tie-break verification (Gate B input)

- Depends on: PG-POC-003
- Description: Verify (score desc, chunkId asc) post-retrieval determinism on PG:
  - pgvector tie order on equal-distance results.
  - tsvector / BM25 ranking determinism on equal scores.
  - Post-retrieval deterministic sort holds across repeated runs.
- Acceptance:
  - Repeated runs produce identical top-K ordering on the frozen corpus.
  - Any residual boundary variance is documented with tolerance (never exact-sequence comparison).
  - Gate B input ready.
- Proposal: §11, §12 Synquest, §18 PG-POC-013.
- Inherits: YDB VIEW-sort finding (post-retrieval sort pattern).

## §6. Phase 0D — Pre-Phase-2 Gates

🟢 PG-POC-005 — Gate A: RLS × ANN composition

- Depends on: PG-POC-003
- Evidence: `pg-poc/005-gate-a.md` (PASS with recorded caveat 2026-09-27) +
  `pg-poc/005-gate-a-plans/` (three verbatim EXPLAINs). Outcome 1 via
  btree-restrict-then-sort on both legs (0.1% and ~33% selectivity);
  adversarial behavioral legs 10/10 eligible, leakage = fail held; metric
  renamed `vec_p95_btree_sort`. Caveat: HNSW never entered a tenant-scoped
  plan at PoC scale (planner cost preference, not structural exclusion) —
  re-verification deferred to Phase 4 with Outcome 2 machinery on standby;
  standby preflight in `pg-poc/005-gate-a-hnsw-preflight.md`.

- Depends on: PG-POC-003
- Description: The analogue of YDB's Gate 0. Test before engine exists: highly selective tenant predicate + vector query + EXPLAIN. Three outcomes decided up front per §8.4:
  - RLS composes pre-ranking at plan level → proceed to benchmarks. PG matches YDB.
  - RLS filters post-ANN → adapter-side explicit predicate composition required. Two-part leakage-proof: (a) EXPLAIN — predicate in same plan node as ANN scan; (b) adversarial test — top-K all cross-tenant, adapter returns eligible candidates. Metric suffix *_predicate_composed vs *_rls_native.
  - Eligible-ID materialize + intersect → thresholds re-examined per YDB Option B rule (absolute-vs-requirement, not adapter-vs-baseline).
  - None holds → PG Synquest collapses; do not benchmark past it. 024A/024B unaffected; Phase 6 falls back to Outcomes 1–5 / partial-Postgres.
- Acceptance:
  - EXPLAIN captured for all three outcome paths.
  - Adversarial leakage test green (Outcome 2 path).
  - Outcome decision recorded with evidence.
  - If Outcome 3: re-examined thresholds documented before Phase 2 benchmark runs.
- Proposal: §8.4, §10 Phase 2, §12 Synquest, §17 gate 1.
- Inherits: YDB Gate 0 (phase2-preflight.md).

🟢 PG-POC-006 — Gate B: comparator validity

- Depends on: PG-POC-013, baseline service, YDB 024A convergence
- Evidence: `pg-poc/006-gate-b.md` (CLOSED 2026-09-29). 024A leg closed by
  R3-CONDITIONAL-PASS (corpus `ydb-poc-corpus-v1`, frozen tolerances;
  verdict `docs/implementation/r3-verdict.json`, routing
  `docs/implementation/r4-decision.md` → PG Phase 2 opens). Tie-break 013
  closed; config at 4 legs.

- Depends on: PG-POC-013, baseline service, YDB 024A convergence
- Description: Before PG Phase 2 begins, verify:
  - 024A convergence (inherited YDB gate) — baseline vs 024A passes on the frozen corpus with the frozen tolerances.
  - PG tie-break determinism — input from PG-POC-013.
  - Extend 028-convergence-config.yaml from 3 legs to 4: legs: [baseline, 024A, 024B, PG] with tolerances pinned before running.
- Acceptance:
  - 024A-vs-baseline convergence verified.
  - PG tie-break determinism verified.
  - Convergence config extended to 4 legs with tolerances pinned.
  - If either fails, PG Phase 2 is blocked (not annotated). Framing revised before proceeding.
- Proposal: §10 Phase 2, §11, §18 PG-POC-006.
- Inherits: YDB 028 convergence config, Phase 2 pre-flight.

## §6b. Phase 0E — Benchmark Comparator

🔴 PG-POC-017 — Benchmark convergence comparator

- Depends on: PG-POC-006 (config), 028 pre-flight rules
- Description: Build the comparator the R0–R5 runbook executes. New module
  `synanton-bench-convergence` — no PG or YDB dependency, reused for the 4-leg
  run (PG-POC-014). Reads `028-convergence-config.yaml`, parses two Q3-format
  JSON run outputs, computes per-leg overlap (lexical/vector/hybrid) and
  eligible-set identity (metadata/eligibility), emits verdicts + readable report.
- Fallback scoping (frozen): "proceed with caveat" applies to
  lexical/vector/hybrid legs only, report-only allowance (raw overlap numbers
  recorded as the caveat; no relaxed threshold enforced). Metadata/eligibility
  failure halts PG Phase 2 and triggers defect investigation — eligible-set
  identity is a contract property, never a caveat. Comparator/environment
  failure aborts the run. Time boxes: metadata/eligibility 3-day investigation
  then escalate (PG stays blocked); perf-leg beyond-allowance 1 week, documented.
- Acceptance: fixture-tested (pass, fail, empty top-K, all-ties edge cases);
  R0.7 green.
- Proposal: §11 (Gate B execution).

## §7. Phase 1 — PostgresSynvaultStore

🟢 PG-POC-004 — PostgresSynvaultStore

- Depends on: PG-POC-003, PG-POC-000, PG-POC-012
- Description: Implement PostgresSynvaultStore with all contract methods: document CRUD, chunks with cursor pagination, mandatory provenance, atomic revision commit (single-node ACID), storage revisions (OCC), tenant isolation via RLS + SecurityContext.
- Acceptance:
  - Full storage-testkit contract suite green.
  - Revision atomicity demonstrated: positive (all four visible), negative (rollback leaves nothing), concurrent (two racers, one wins, loser fails, retry-after-reread succeeds).
  - Provenance mandatory; empty provenance rejected.
  - putDocument metadata-only.
  - Cursor pagination stable across pages and out-of-order requests.
  - supportsStorageRevisions=true with conformance evidence.
  - RLS tenant isolation demonstrated at all four selectivity levels.
- Evidence: `java/synvault-postgres` — `PostgresSynvaultStoreTest` (contract
  8/8), `RevisionAtomicityTest` (rollback + racer + metadata-only 3/3),
  full module 23/23 green. Schema PK/FK `uuid`→`text` (domain ids
  `tenant_a`/`d1` cannot round-trip; inventory expanded). OCC via
  `SELECT ... FOR UPDATE` + revision guard; atomic single transaction;
  publication relay to outbox only on revision path. failAfterChunks hook
  is test-only (negative path), mirrors YDB 021 shape.
- Pattern transfer (pattern-notes.md PN-1/2/3): parameterization
  held by default — YDB needed it for performance, PG for
  correctness/security; same policy, per-backend motive. OCC mechanism
  differs from YDB (row locks vs serializable-RW abort) with identical
  contract semantics; the racer test is the drift guard.
- Proposal: §8.1, §10 Phase 1, §12 Synvault.
- Reuses: YDB 021 contract tests, atomicity tests, PlanAssertions pattern.
- Note (YDB-041 non-transfer): PG tolerates inlined literals far better than
  YDB — do not expect the 250× finding to reproduce, and do not skip
  parameterization thinking "PG is fine with literals anyway." Parameterize
  by default for security and correctness all the same.

## §8. Phase 2 — PostgresSynquestEngine

🔵 PG-POC-007 — PostgresSynquestEngine

- Depends on: PG-POC-005 (Gate A), PG-POC-006 (Gate B), PG-POC-013
- Description: Implement PostgresSynquestEngine for lexical, vector, hybrid retrieval. Pre-ranking eligibility per Gate A outcome. Metadata filtering pushed into SQL (not post-fetch). Tie-break post-retrieval per PG-POC-013.
- Acceptance:
  - Full contract suite green.
  - Lexical retrieval, default in-core `tsvector`+GIN per the 002 decision
    (acceptable iff lexical overlap vs baseline stays within the R3
    tolerance family); `pg_search` is the recorded fallback, evaluated in
    parallel where pullable — never a hidden second implementation.
    Query semantics named: disjunctive `to_tsquery` (mirrors Lucene
    QueryParser default OR), `ts_rank` custom (not BM25), sub-384 vectors
    zero-padded to the fixed column (orthogonal stays orthogonal; >384
    rejected). All three recorded for the 007-4 probe.
  - Vector ANN: planner-chooses access path (no forced index); metric
    carries the topology (`vec_p95_btree_sort` until a plan says
    otherwise). IVFFlat added beside HNSW. Dimension fixed at column
    creation — switching models needs ALTER + index drop/rebuild, cost
    scaling with table size (same shelf as the YDB finding). Synthetic-384-d
    recall stays unclaimed.
  - Distance operator named: cosine `<=>` per production COSINE parity
    (Gate A's `<->` L2 probe was plan-shape evidence, not the served path;
    score stored as `-distance` so higher-better ordering is uniform).
  - IVFFlat deferred-DDL rule (same class as the YDB empty-table-index
    finding): IVFFlat trains on what's present, so it is created AFTER the
    corpus loads — never in schema setup. Test path creates it post-seed;
    production path is a post-load migration step.
  - Per-query topology: plan shape can differ per selectivity, so the
    topology travels per query, not per leg — EXPLAIN per selectivity leg,
    topology recorded per leg, hard assertion is eligibility-in-plan
    (tenant restriction before ordering), never "must use index" (at PoC
    scale seqscan can be the correct planner choice). 028e carries the
    per-query field into Q3 output as a follow-up obligation.
    Observed 007-3 (30k Gate-A-shaped corpus, IVFFlat post-load):
    0.1%-selectivity leg → btree-sort, ~33% leg → ivfflat — per-query
    variance confirmed, HNSW serves neither (Gate A caveat holds).
  - Fusion parity probe (007-4, before any hybrid number): five named axes
    vs YDB's **recorded** 024B behavior (artifact reference, no
    cross-container dependency) — term matching
    (conjunctive/disjunctive), RRF k (60 vs PG default), score space
    (rank vs normalized), tie ordering (per 013), lexical query semantics
    (plainto/to_tsquery/websearch/phraseto vs frozen Lucene defaults).
    Each axis matching or divergent-with-a-name; output written to
    `build.json` beside load_ms so the topology travels with the number
    for 028e/R3.
  - Hybrid leg gated by the parity probe, not before it.
  - EXPLAIN assertions on all index-dependent queries (analogue of YDB PlanAssertions).
  - Metadata filtering in SQL: EXPLAIN shows the predicate in-plan plus a
    behavioral large-eligible-set leg (YDB P1-4 class, caught by
    construction).
  - Engine determinism: two runs on the same corpus produce identical
    top-K per query (modulo timing_ms). No RunLeg home exists — 007 owns it.
- Self-arming suite: the contract tests gate on capability flags, so no
  suite modification is needed as flags flip — each evidenced flag just
  arms more tests. Progress is measured by failures shrinking, same
  structural property as "conformance matrix is code, not docs."
- Pre-ranking eligibility per Gate A outcome; metric suffix on every eligibility-filtered number.
- Post-retrieval sort (`score desc, chunkId asc`) applied on every query — server tied order is deterministic but not chunkId-asc (013 finding); verified by the 013 tie-break determinism test.
- Tie-break deterministic; convergence gates pass.
- HNSW-at-scale stays with the standby preflight (`005-gate-a-hnsw-preflight.md`); 007 measures what the planner selects at PoC scale.
- Temporal rejection explicit: `capabilities().temporal() == false` →
  non-empty TemporalExtension rejected cleanly (same shape as the YDB leg),
  its own acceptance — not folded into eligibility generically.
- Proposal: §8.3, §8.4, §10 Phase 2, §11, §12 Synquest.

🔵 PG-POC-008 — Eligibility / side channels / temporal

- Depends on: PG-POC-007
- Description: Implement and test:
  - Eligibility composition (security + tenant) enforced at candidate generation, verified by EXPLAIN + adversarial test.
  - Side-channel eligibility (highlights, counts, facets obey eligibility).
  - Temporal extension point rejection (non-empty TemporalExtension rejected cleanly when capabilities().temporal() == false).
- Acceptance:
  - Cross-tenant leakage tests pass (reads, writes, search, side channels, rebuild, replay under load).
  - Side channels eligible-only.
  - Temporal rejection clean.
- Proposal: §7.4, §12 Synquest.

## §9. Phase 3 — Projection Consistency

🔵 PG-POC-009 — Projection consistency

- Depends on: PG-POC-007
- Description: Same tests as YDB Phase 3: freshness, replay, out-of-order, generation promotion, tenant-scoped pending. Measure commit → search-visible latency on PG.
- Acceptance:
  - Freshness within inherited threshold or explicitly marked unclaimed.
  - Generation-scoped rebuild with atomic promotion.
  - Regression prevention via ordering key + generation ID.
  - Tenant-scoped pending() consumption, no cross-tenant scan.
- Proposal: §9, §10 Phase 3.
- Reuses: YDB 029/030/031 tests adapted to PG.

## §10. Phase 4 — Scale, Failure, Cost

🔵 PG-POC-010 — Scale, failure, cost (incl. horizontal boundary)

- Depends on: PG-POC-004, PG-POC-007, PG-POC-009
- Description: Same dimensions as YDB Phase 4, with the addition of a horizontal scaling test: where does Postgres stop scaling, and does that boundary matter for Synanton's projected workload?
- Acceptance:
  - Steady state, ingestion bursts, node failure, restart/recovery, index rebuild.
  - Filter selectivity at 0.1 / 1 / 10 / 100%.
  - Horizontal boundary identified and documented.
  - Cost model produced per YDB §16.1 + PG additions (managed PG cost, horizontal scaling cost, operational overhead).
- Proposal: §10 Phase 4, §12 Cost.

## §11. Phase 5 — Migration Tooling

🔵 PG-POC-011 — Migration tooling (reuse YDB migrator)

- Depends on: PG-POC-004, PG-POC-007
- Description: Reuse the YDB PoC's migrator if adapter-agnostic. If not, that's a finding. Migrate the frozen benchmark dataset from Cassandra + ingestion-cache to PG.
- Acceptance:
  - Migration reproducible on the frozen dataset.
  - Checksums validate.
  - Rollback tested.
  - If migrator needed modification, the modification is documented and the reusability finding recorded.
- Proposal: §10 Phase 5, §17.
- Reuses: YDB 035 migrator.

🔵 PG-POC-014 — Four-legged benchmark run

- Depends on: PG-POC-010, PG-POC-006, all four adapters green
- Description: Run the 4-leg benchmark: baseline vs 024A vs 024B vs PG. Apply the frozen thresholds, topology annotations, tie-break discipline, and per-adapter convergence scoping.
- Acceptance:
  - All four legs run on the frozen corpus with frozen thresholds.
  - Every PG metric carries its topology (scan type, RLS pre/post-ANN, payload fetch pattern, extension versions, hybrid flag, dims+bias).
  - Convergence gate passes for each adapter vs baseline.
  - Results recorded per adapter, not as a single ratio.
- Proposal: §11, §10 Phase 2/4.

## §12. Phase 6 — Decision

🔵 PG-POC-015 — Phase 6 contribution

- Depends on: PG-POC-014, PG-POC-004, PG-POC-007, PG-POC-009, PG-POC-010
- Description: Prepare the PostgreSQL contribution to the joint Phase 6 decision package. Fill the 036 template's evidence slots with PG data. Apply Decision 4 criteria (production migration gate) to PG.
- Acceptance:
  - PG evidence package complete.
  - All Decision 4 criteria evaluated for PG (or explicitly marked unclaimed).
  - Outcomes 6–9 assessed with measured evidence.
- Proposal: §0.2, §15 Decision 4.

🔵 PG-POC-016 — Joint Phase 6 with YDB

- Depends on: PG-POC-015, YDB 036
- Description: Per proposal §0.2, Phase 6 is a joint decision: it runs only when both PoCs have produced their evidence packages. Select from Outcomes 1–9. Neither PoC closes the decision unilaterally.
- Acceptance:
  - Both evidence packages complete.
  - Four-legged comparison produced.
  - Outcome selected and documented.
  - If Outcome 9 (hybrid): spin off dedicated hybrid-evaluation track (per §14).
- Proposal: §0.2, §14 (Outcome 9 note), §15 Decision 3/4.

## §13. Proposal → Ticket Traceability

| Proposal section | Tickets |
|---|---|
| §0.1 (§14–15 gate) | 000, all |
| §0.2 (joint Phase 6) | 016 |
| §2.1 (plane mapping) | 000 |
| §7.1–7.3 (architecture) | 000 |
| §7.4 (security) | 003, 008 |
| §8.1–8.2 (schema, indexes) | 003 |
| §8.3 (capabilities to validate) | 004, 007, 008 |
| §8.4 (Gate A) | 005 |
| §9 (consistency) | 009 |
| §10 (PoC plan) | all |
| §11 (comparator discipline, Gate B) | 006, 013, 014, 017 |
| §12 (acceptance) | 004, 007, 008, 009, 010 |
| §13 (risks) | all |
| §14 (alternatives, Outcome 9 note) | 016 |
| §15 (decisions) | 016 |
| §17 (recommendation) | 000, 005, 006 |
| §18 (ticket sketch) | 000–016 |
| §19 (nine-rule discipline) | §0 (rules) + 012 |

## §14. Done Criteria (per ticket class)

🔴 Blocker: Closed when acceptance is met and the artifact is committed to the branch with a reference from the proposal's Decision section. Architecture or designated owner signs off. "In progress" is not a closed state for Phase 0 exit.

🟠 Must-hold: Closed when the contract test or rule is implemented, passes across all four adapters (Cassandra, YDB, in-memory, PG), and is enforced in CI. Must-holds are continuous — re-verified at each phase boundary; regression reopens the ticket.

🔵 PoC execution: Closed when the deliverable is committed and reviewed by the ticket owner's counterpart. Benchmarks close with a results table plus interpretation; migration tickets close with a reproducible run and rollback evidence.

🟡 Production gate (Decision 4 criteria): Closed only when the Phase 6 decision package demonstrates the item against measured thresholds.

## §15. Suggested Owners

| Ticket | Owner |
|---|---|
| 001, 002 | Platform Eng |
| 000 | Platform Eng + Architecture |
| 003, 012 | Platform Eng |
| 013 | Platform Eng + Search Eng |
| 005 | Search Eng (with Platform Eng) |
| 006 | Search Eng + Platform Eng |
| 004 | Platform Eng |
| 007, 008 | Search Eng |
| 009 | Platform Eng |
| 010 | Platform Eng + Search Eng |
| 011 | Platform Eng |
| 014 | Search Eng |
| 015 | Workstream |
| 016 | Workstream + Architecture |

## §16. Execution Notes

- Phase 0A–0D are prerequisites for Gate A/B. Do not open Phase 1/2 until both gates pass. Same discipline as YDB §0.1.
- PG-POC-005 (Gate A) is the load-bearing test. Run it before writing the engine. If RLS post-ANN and adversarial proof fails, the Synquest leg collapses; do not build past it.
- PG-POC-006 (Gate B) inherits YDB's 024A convergence dependency. If the baseline service is still unavailable, Gate B is blocked. PG can proceed through Phase 0B/0C while waiting.
- Reuse is literal, not "similar." The 004 matrix, 005 corpus, 006 thresholds, 028 pre-flight, and 028 config apply unchanged (extended to a 4th leg for the config, not replaced).
- Every metric carries topology. No exceptions. The metric name is where the topology lives.
- Report every scope-widening risk as it's discovered, not at review. The YDB PoC's evidence-scope rule applies from the first commit.

## §17. What to Do Now

In the PG branch, the first three commits should be:

1. Ticket plan committed (this document).
2. PG-POC-001 + 002 — pins and stability inventory. No PG code; a manifest and a table.
3. PG-POC-000 — verify inherited infrastructure; extend provider selection to register PG as a candidate (with startup validation rejecting PG until adapters exist).

These three can land in parallel with YDB PR #51 already merged. Nothing depends on the baseline service or on Gate A/B.

After that, Phase 0C begins (schema + RLS + tie-break), then Phase 0D (Gate A + Gate B). Phase 1 opens only after both gates pass.
