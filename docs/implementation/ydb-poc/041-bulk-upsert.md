# YDB-ADAPTER-BULK-UPSERT — Batch Upserts with OrderingKey Preservation

**Status:** Filed (blocks 024b full leg; discovered 2026-09-28, 10k sanity)
**Track:** YDB adapter (not harness, not comparator)

## Finding

`YdbSynquestEngine.upsert` performs 3 sequential serializable operations per
row (1 ordering read + 2 commits). At 160k rows ≈ 480k commits; the first
500-row batch never returned in 40 min. The full 024b leg is infeasible
through this path at any harness tuning.

## Scope

Replace per-row (1 read + 2 commits) with per-batch (1 read + 1 commit):

- One ordering read per batch — single query fetching orderingKeys for all N
  row ids.
- In-memory comparison — drop rows with incoming key ≤ current (regression),
  keep the rest.
- One transaction per batch — UPSERT survivors, projections + vectors together.

Ordering semantics preserved; transaction count drops 3N → 2 per batch.

## Load-path caveat (not "setup-only")

This changes measured load behavior, not just harness speed:

- `build.json` load timing will differ — record the new number, not the old.
- Index backfill under bulk inserts may differ from per-row — re-measure the
  readiness wait against the new path.
- Phase 4 cost model consumes the bulk numbers. The per-row cost profile is
  superseded, not averaged.

## Acceptance

- 10k subset loads in < X min (measure first; X set so 160k extrapolates < ~30 min).
- Final state after bulk load == final state after per-row load, same rows,
  same order (equivalence fixture).
- Ordering guard: stale + fresh events for one chunk → only fresh persists.
- Mixed batch (fresh + stale rows) → exactly the fresh subset written.

## Measure and record

- Rows/s at 10k, extrapolated to 160k.
- Commit count reduction (per-row vs per-batch).
- Index backfill behavior vs per-row path.
- Effect on `build.json` load timing.

## Not in scope

- Query path changes. Index architecture changes. CLI wrap (independent).

## Architectural note (Phase 4 input)

024A never hit this: Lucene's index is in-JVM (B.2 load CPU-bound, no
round-trips); YDB's is client-server (network-bound, per-commit cost
exposed). Write-path batching discipline is a YDB-specific operational
concern — a material backend difference for the comparison, not a defect.
