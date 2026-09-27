# Decision Record — Corpus, 028 Scope, PG Emitter

**Status:** Decided (2026-09-27)
**Closes:** Escalation `f887f31`
**Branch:** `DESIGN-PostgreSQL` (cross-track; affects YDB-track workstream — YDB workstream notified)
**Supersedes:** Previous framing of 028 as "pending execution"

## D1 — Corpus = v1 generator output

The 4-leg benchmark runs on the v1 corpus, produced by a generator that must
be built.

Rationale:

- BaselineBench is single-tenant. The frozen 028 pre-flight mandates
  eligibility legs at four selectivity levels across 50 Zipf tenants.
  BaselineBench cannot produce those legs; using it would require deleting
  mandatory legs from 028 — un-answering a Must capability.
- v1 is the only corpus shape that satisfies the frozen pre-flight as written.

Consequence — 006 thresholds are void:

- The 006 run record (docs=20000, chunks=8, seed 42, 40 golden queries,
  single tenant) does not match the 005 definition (120 golden queries,
  50 Zipf tenants). Same 160k aggregate, different shape.
- The generator was never committed; the corpus the thresholds were measured
  on is not reproducible.
- 006 re-measurement and re-freeze is in scope. New thresholds land before
  028 runs. Same handling as the hybrid-anomaly void.

Ratified: corpus = v1 generator output.

## D2 — 028 re-scoped as a build phase

028 becomes a build, not a run. Five artifacts, each with an owner and an
estimate.

| # | Artifact | Owner | Estimate | Blocks |
|---|---|---|---|---|
| 028a | v1 corpus generator (50 Zipf tenants, 120 golden queries, 160k chunks, seed 42) | YDB | 3–5 days | Everything |
| 028b | Multi-tenant BaselineBench + Q3 emitter | YDB | 3–5 days | Baseline leg |
| 028c | 024A Q3 emitter | PG | 1 day | 024A leg |
| 028d | 024B Q3 emitter | YDB | 1 day | 024B leg |
| 028e | PG Q3 emitter (= PG-POC-018) | PG | 1 day | PG leg |

Critical path: 028a → 028b → re-measure 006 → 028c/d/e (parallel) → R0 →
R1–R5. Total estimate: 2–3 weeks, sequential on the YDB side; PG emitters
build in parallel once 028a's output format is pinned.

Not building: BaselineBench-to-v1 adapter. If the corpus is v1, BaselineBench
is replaced, not adapted. Artifact 028b extends the existing harness's output
shape, not its corpus format.

Ratified: 028 re-scoped. Ticket numbers 028a–028e assigned on the YDB tracker.

## D3 — PG-POC-018 — Multi-tenant Q3 Emitter

PG-track owns the Q3 emitter for the PG leg.

Scope:

- Read v1 corpus output (multi-tenant, Zipf-distributed).
- Execute 120 golden queries across modes (lexical/vector/hybrid) and filters
  (none/tenant/metadata/eligibility).
- Emit Q3 JSON matching the strict schema: run_id, corpus, queries[] with
  top_k[], eligible_set[], timing_ms.
- Determinism: same seed → same corpus → same results.

Estimate: 1 day. Dependency: 028a's output format must be pinned. PG drafts
the emitter against the format spec immediately; integration waits for 028a
to land.

Ratified: PG-POC-018 created on the PG tracker, blocked on 028a format spec.

## Immediate next steps

This week:

- YDB-track owner starts 028a (generator). Single blocking item for everything
  downstream.
- PG-track owner drafts PG-POC-018 against the (to-be-pinned) format spec.

After 028a lands:

- YDB builds 028b (multi-tenant BaselineBench + Q3 emitter).
- Re-measure 006 on v1. Re-freeze thresholds.
- 028c, 028d, 028e build in parallel.
- R0 re-runs. R1–R5 execute.

After R4:

- If convergence passes: PG Phase 2 opens.
- If convergence fails per the pre-decided routing: caveat
  (lexical/vector/hybrid) or halt-and-investigate (metadata/eligibility).

## State after this record

| Track | Status |
|---|---|
| YDB | 028 re-scoped as build (028a–028e); critical path named; owners assigned |
| PG | Paused; PG-POC-018 drafted; Phase 2 opens after R4 |
| Joint Phase 6 | Gated on 028 completion + 006 re-freeze + both evidence packages |

No further decisions needed until the build phase completes. The escalation's
two questions are resolved. The critical path is executable.
