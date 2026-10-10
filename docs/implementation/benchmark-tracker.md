# Benchmark Tracker — Four Targets

Target-named scheme (replaces owner/mixed naming). Alias map at the bottom
covers the transition; history is not rewritten.

| Target | Adapter | Emitter ticket | Branch | Q3 output | Status |
|---|---|---|---|---|---|
| Baseline | Lucene (in-JVM) | BENCH-BASE (was 028b) | merged (#65, #68) | runs/baseline-v1.json | ✅ Closed 2026-09-28 (96/120, 24 structural; closeout on main) |
| Cassandra | 024A (ingestion-cache/Lucene) | CASS-EMIT-024a (was 028c) | DESIGN-emit-cassandra | runs/cassandra-v1.json | ✅ B.2 green 2026-09-28 (sha 85921bb2, 96/120 non-empty, 24 structural) |
| YDB | 024B | YDB-EMIT-024b (was 028d) | DESIGN-emit-ydb (was DESIGN-YDB-028d) | runs/ydb-v1.json | Paused — 041 root cause deferred to Phase 4 (see 041-experiment-record.md) |
| PostgreSQL | PostgresSynquestEngine | PG-EMIT-pg (was 028e) | DESIGN-emit-pg (was DESIGN-PG-028e) | runs/pg-v1.json | Scaffold only; real run post-R4 |

Pre-R3 gates (all must close before R3; parallel with 041):

| Gate | Owner | Status |
|---|---|---|
| 006 re-freeze (thresholds from baseline-v1.json) | andreminin | Unblocked now — runs parallel with 041, not serial after C.2. Percentile rule: legs flagged `structural_empty` are EXCLUDED from threshold math (no signal, not zero signal) |
| 024B/PG fusion-semantics check (pre-ranking invariant) | YDB workstream (024B) · PG workstream (PG) | **024B CLOSED**: iterative over-fetch on all 3 legs (page-until-full, 20k cap, fail-loudly `SCAN_CAP_HIT` tripwire); regression + cap tests green. PG still deferred to leg activation |

Cross-cutting:

| Component | Ticket | Status |
|---|---|---|
| Emitter module (shared) | BENCH-EMIT-* (was A.1–A.6) | Phase A complete, green |
| Comparator | BENCH-CMP (was PG-POC-017) | Green (12/12 + 4/4 contract) |
| Corpus generator | 028a.1–028a.11 | Complete on main |
| Phase 4 backlog | — | NONE experiment, index isolation, BulkUpsert-RPC, clock skew, resource broker, 028-4.X KNN-vs-text split (owner TBD — pre-R3 backlog, non-blocking, tracked here so it isn't lost) |
| Harness amendment 2026-09-30 (PN-7) | — | RunLeg-based runs NEVER executed the truncate step (`engine.truncate()` fetched-and-dropped the lambda). Observed clean state came from fresh prefixes/temp dirs, not truncate — earlier "truncate-on-start verified" claims read as "redundant mechanism, unverified". Fixed with `.run()` (all legs); structural prevention (void return) is a post-R3 refactor. YDB close never ran either (transport leak, harmless one-shot). |

## Forward plan (Benchmark Runner + Vector Engine Selection)

- Execution plan: [benchmark-runner-vector/INDEX.md](./benchmark-runner-vector/INDEX.md) — 48 canonical tasks
  (BR 12 + VEC 24 + DOC 12). Track files: [BR](./benchmark-runner-vector/track-a-benchmark-runner.md) ·
  [VEC](./benchmark-runner-vector/track-b-vector-engine.md) · [DOC](./benchmark-runner-vector/track-doc-documentation.md).
- This tracker stays the execution record for the four-target benchmark; the new plan's B5/VEC-B5.x
  runs report back here. No duplication: task definitions live in the plan, run results live here.

### Week-1 gate: closed 2026-10-01

- Track A / Track B / Docs owner: Andrei Minin (all three). Recorded in
  [benchmark-runner-vector/INDEX.md](./benchmark-runner-vector/INDEX.md) §1.
- Legal + architecture reviewers TBD (needed at B0/B6 review points, not blocking).
- Week-2 gate (Manifest + Result schema + metric taxonomy freeze) schedulable.

### VEC-B0.1 — License matrix: landed (legal review open, non-blocking)

- Scope: primary engines + pg-extensions + adjacent incl. Tantivy/Quickwit rows.
- Matrix: [vector-license-matrix.md](./benchmark-runner-vector/vector-license-matrix.md) landed — 034b08e (PR #93, 2026-10-01).
- LICENSE reads complete: pgvectorscale PostgreSQL/High (TSL-lineage hypothesis refuted),
  pgvecto.rs Apache-2.0/High, Quickwit Apache-2.0/High; Verified-restricted retired uninstantiated.
- Final matrix state incl. Weaviate mixed-license row (BSD-3-Clause OSS core + proprietary enterprise wl/; PR #104). All 12 universe-touching entries read; no managed-service restriction found.
- Elasticsearch row is the only license-gated decision cell (SSPL/ELv2/AGPL) — legal sign-off required.
- Legal review checklist open; dependency-level scan deferred to legal review.

### VEC-B0.2 — Deployment-mode + managed-availability matrix: landed

- Scope: runtime-integration model + engine × context deployment matrix + engine × cloud managed-availability matrix + embedded-edge cases.
- Matrix: [vector-deployment-modes.md](./benchmark-runner-vector/vector-deployment-modes.md) landed — 02fd620 (PR #96, 2026-10-07).
- Declared 11-engine universe; divergence from VEC-B0.1's 21-engine license set recorded in §5a (combined rows retained, Quickwit split, PG-extension + adjacent exclusions).
- Phase-5 measurement candidates recorded in §5b (Vespa Azure, Quickwit managed, Cassandra+Lucene managed, Tantivy managed).

### VEC-B0.3 — Hardware + scalability + operational complexity: landed

- Scope: CPU/memory/storage profiles per engine + scalability + operations.
- Profiles: [vector-hardware-profiles.md](./benchmark-runner-vector/vector-hardware-profiles.md) landed — 425c374 (PR #98, 2026-10-07).
- 11-engine universe (same as B0.2); no measured cells — all sourced or n/a-with-reason; assumed cells routed to Phase-5 in §8.
- Cross-engine findings: overhead type non-uniform (§6a), tier comparability non-universal (§6b), source vintage spans 4 years (§6c).
- Open: storage footprint gap (10/11 engines blank) flagged as batch Phase-5 candidate.

### VEC-B0.4 — Per-context profiles: landed

- Scope: five contexts (cloud-managed, cloud-self-hosted, on-prem, docker, embedded) × disqualified / viable / default / trade-offs.
- Profiles: [vector-context-profiles.md](./benchmark-runner-vector/vector-context-profiles.md) landed — e3028d2 (PR #101, 2026-10-08).
- Defaults: PostgreSQL + pgvector (cloud-managed); Qdrant (cloud-self-hosted, on-prem, docker); Lucene standalone (embedded).
- License top-up reads complete (all 12 universe-touching entries: no managed-service restriction found; Weaviate wl/ enterprise split noted, no use restriction on OSS core).

### VEC-B0.5 — Cross-context recommendations: landed

- Scope: context → recommended / alternative / notes summary table.
- Evidence: §3 of [vector-context-profiles.md](./benchmark-runner-vector/vector-context-profiles.md) — section landed with e3028d2 (PR #101, 2026-10-08).
- Satisfied as written; acceptance ("every context has a recommendation") met — five contexts, one default each.
- Known deviation: card describes `context → recommended / alternative / notes`; §3 carries `Context | Default | Viable | Disqualified`. Accepted at review; no edit.

### Gate B0: closed 2026-10-08

- Owner: Andrei Minin (Track B). B0.1–B0.5 landed and mirrored above — primary artifact PRs #93/#96/#98/#101 (+fixes #99/#102, matrix touch-up #104); tracker PRs #92/#94/#97/#100/#103/#105/#106.
- Recorded deviation: B0.5 satisfied via §3 (shape differs from card; accepted at review, no edit).
- Carried open: legal sign-off + dependency scan (B0.1); storage-footprint batch measurement. If legal returns a restriction on a universe engine, the gate reopens for B0.4 review.

### VEC-B1.1 — VectorRetriever port + DTOs: landed

- Scope: VectorRetriever port, VectorSearchRequest (pre-embedded vectors only), VectorSearchResult (no highlights), VectorProjection, Projection sealed interface (incl. ChunkProjection gains `implements Projection`).
- Evidence: PR #110 (5be66de) — interface + DTO files + JSON round-trip tests green, boundary guard green.
- Decisions: Reading 1 (caller embeds, retriever never sees text); reuse-by-shape (records final, no subtyping); capabilities reuse with lexical-only flags false by contract.
- Carried gap: capabilities() reuses SearchCapabilities — vector-specific flags (index type, distance metric per B0.3 §2a) not representable; no B1 task owns this. Revisit if B3 per-adapter work needs it.
- B1.4 note: facade synthesizes an empty highlights map when translating back to SearchResult.

### VEC-B1.2 — Writer port sealed upsert: landed

- Scope: upsert(List<ChunkProjection>) → upsert(List<? extends Projection>) + narrowing contract; ordering-key/generation + rebuild-semantics javadoc; VectorProjection forward-ref fix.
- Evidence: PR #111 (d5b5a78) — api + inmemory suites green; Postgres contract suite 10/10 on live container; YDB/Cassandra test-compile green (CI covers).
- Deviations: no VectorIndexWriter port (tenant/operator two-port decomposition preserved); no new rebuild (already on SynquestIndexAdmin); adapters fail UNSUPPORTED on unimplemented kinds, never silently drop.
- Follow-up (non-blocking, hygiene): failure-path metric inconsistency — Postgres records no metric on pre-pass UNSUPPORTED failure; Cassandra/YDB record false; InMemory uses track(). Reconcile on a B1.2-adjacent branch.

### VEC-B1.3 — Wrap adapters as VectorRetriever: landed

- Scope: Cassandra + Lucene, YDB, PostgreSQL + pgvector each expose their vector path through VectorRetriever; no behavior change.
- Evidence: PR #115 (2213f97) — three wrappers + three live equivalence suites green; 33 existing contract tests green across the three adapters.
- Deviations: card says "test suite unchanged"; Option 2 adds three equivalence suites at Operator direction (live-container validation before B1.4 facade wiring). Accepted at review.
- Design note: wrappers delegate through engine.search(mode=VECTOR), not adapter internals — zero behavior change is structural. Three wrappers are byte-identical; a single DelegateVectorRetriever in synquest-api would pass the boundary guard. Revisit before B3 authors Milvus/Qdrant retrievers against the per-adapter pattern.
- Carried gap: capabilities() hardcodes vector-only shape — underlying engine capability detail (filters, index type) not surfaced; overlaps B1.1's recorded VectorCapabilities gap.

### VEC-B1.4 — Routing facade: landed

- Scope: RoutingSynquestEngine in synquest-api routes mode=VECTOR + embedding-present + narrow fields through VectorRetriever; all other requests pass through to the engine. Wide vector+embedding requests fail loud naming the fields.
- Evidence: PR #118 (8490f81) — facade + unit tests green, api suite + boundary guard green.
- Reading-1 claim: zero change for direct-engine callers; facade callers get the narrow retriever contract or an explicit IllegalArgumentException — no silent narrowing. Empty highlights on routed results.
- Deviation from card: card implied pass-through; fail-loud on wide vector requests adopted at operator direction (silent narrowing rejected).
- Confirmed at review: RelevanceFilters is single-field (mustMatchMetadata), so the emptiness check is complete — no silent filter-dropping.
- Precedent: first behavioral class in synquest-api; module scope "port-owned types" extended to include the routing facade.
- Carried: B2.1 owns retriever selection — facade holds one retriever, constructor-injected.
- Widening escape hatch: if VectorSearchRequest grows filters/temporal/minScore, the corresponding clause comes off both predicates (isNarrowVectorRequest, isLossyVectorRequest).

### VEC-B5.2 — Composition legs executed (partial): comp 1, 2+3, 4+5, 6

- Comp 1 (Cassandra+Lucene, BM25-only): live synquest run, 10 queries, p50/p95 4.5/36ms, recall null (gold unannotated). Local artifact.
- Comps 2/3 (Milvus/Qdrant vector legs): distinct-vector self-match runs, recall@10 1.0 (correctness, not quality), p95 50.5/27.1ms.
- Comps 4/5 (PG lexical legs, shared metadata evidence): recall@10 1.0, p95 21.6ms.
- Comp 6 (YDB lexical legs): recall@10 1.0, p50/p95 19.8/40.8ms.
- Dense-leg quality + hybrid runs blocked on real embeddings (mock-constant vectors prove plumbing only). Artifacts local per `runs/` convention (untracked).
- Machinery: Synquest REST executor + endpoint probe + Q3 bridge in `tools/benchmark-runner` (59+ tests green).

### VEC-B5.3 — Vector-engine effect isolated

- Lucene vs Milvus vs Qdrant on identical mini workload: recall 1.0 ×3, p50s 2–4ms, p95s 27–51ms. No selection signal at this scale; all three viable. Evidence: `b5-effect-isolation.md`.
- B5.4 (metadata-store) + B5.5 (integration): deferred — need hybrid runs, no table faked.

### VEC-B5.6 — Crossover measured (BM25-only scope)

- 10/10 identical hit sets parallel vs sequential, live. No crossover identifiable without dense legs; procedure in place via per-leg trace timings.

### VEC-B6 — Synthesis published (draft for review)

- Framework: `docs/architecture/vector-engine-selection.md` — matrix with measured/unmeasured cells, per-context recommendations with evidence notes, sources, follow-ons F-1–F-8 (owners TBD).
- B0.4 profiles stay frozen as task evidence; functional overlay lives in the framework.
- NOT yet reviewed: architecture + legal sign-off required before production use.

### DOC-D3.3 — Pattern library additions: landed

- Appended PN-9…PN-13 to `docs/implementation/pg-poc/pattern-notes.md`
  (the library seed; house rules append-only/chronological kept): roles-not-
  positions composition, unmeasured-cell flags, score-space portability,
  identical-input attribution, runbook-by-execution. Each names its Track B origin.

### DOC-D4.1 — Cross-reference pass 2026-10-10: landed

- Mechanical citation check over all 10 DOC-evidence docs: every referenced
  path resolves (6 same-directory sibling links + schema basenames verified
  against `schemas/benchmark/`). One genuine fix: framework §4 cited
  `b5-effect-isolation.md` bare — corrected to the full
  `../implementation/benchmark-runner-vector/` path.
- Standing cadence: repeat this pass at every phase gate; gate checklists
  carry DOC items (see Week-2 entry below).

### Week-2 gate: open (unscheduled)

- Scope: Manifest + Result schema + metric-taxonomy freeze (BR-A0.2/A0.3/A0.4).
- Owner/date: TBD — schedulable since Week-1 closed; unscheduled until BR-A0.1 requirements land.
- DOC items for the gate checklist: D0 refs current vs frozen schema versions;
  D4.1 cross-reference pass repeated; runbook validation log still green
  against the frozen CLI.

## Alias map (transition only — drop after all branches renamed)

- 028b → BENCH-BASE · 028c → CASS-EMIT-024a · 028d → YDB-EMIT-024b ·
  028e → PG-EMIT-pg · A.* → BENCH-EMIT-* · PG-POC-017 → BENCH-CMP
- runs/024a-v1.json → runs/cassandra-v1.json · runs/024b-v1.json → runs/ydb-v1.json
- legs [baseline, 024A, 024B, PG] → [baseline, cassandra, ydb, postgres]

## Rename order

1. B.2 completes as-is; artifact renamed to runs/cassandra-v1.json at CLI-wrap.
2. Branch + config + tracker rename pass before 024b starts.
3. Baseline/PG rename on next touch.

- Status: rename pass pending (024b paused, not started); alias map active until drop condition met.

## Parked (single source — lands only here, not in branch-local files)

- `--engine baseline` wiring + README boot-jar line: parked until 028b line
  and emitter line both merge to main; then one tiny PR off main.
  (Two branch-local copies would drift — this section is the only record.)
