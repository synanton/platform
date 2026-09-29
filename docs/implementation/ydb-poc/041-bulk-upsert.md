# YDB-ADAPTER-BULK-UPSERT — Batch Upserts with OrderingKey Preservation

**Status:** Filed (blocks 024b full leg; discovered 2026-09-28, 10k sanity)
**Track:** YDB adapter (not harness, not comparator)

## Finding

`YdbSynquestEngine.upsert` performs 3 sequential serializable operations per
row (1 ordering read + 2 commits). At 160k rows ≈ 480k commits; the first
500-row batch never returned in 40 min. The full 024b leg is infeasible
through this path at any harness tuning.

Measured baseline (041.1, 2026-09-28): **4.3 rows/s on the 100-row fixture
(23s)** — 160k extrapolates to ~10.5 hours. The batch path reports its own
rows/s in `build.json`; this number is the reference it must beat.

Premise status (2026-09-29): an interim reading held the fixed ~35s
per-commit cost as falsifying "fewer commits = faster." Root cause was
environmental (unmounted `/ydb_data` on overlayfs + residual compaction;
see External-storage rule). Observation stands, conclusion updated: on
non-degenerate storage, per-commit cost is whatever the volume-mounted
sanity measures, and fewer commits amortizes normally. Strategy validated,
contingent on storage.

## Scope

Replace per-row (1 read + 2 commits) with per-batch (1 read + 1 commit):

- One ordering read per batch — single query fetching orderingKeys for all N
  row ids.
- In-memory comparison — drop rows with incoming key ≤ current (regression),
  keep the rest.
- One transaction per ≤100-row chunk — UPSERT survivors, projections +
  vectors together. (100, not 500: server AST node cap is 1M; 500 rows build
  ~1.1M nodes. Node-bound.)

Ordering semantics preserved; transaction count drops 3N → ~N/100 per batch.

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
- Commit count is per 100-batch (10k rows → 100 commits, was 30k) — the
  ticket's "batch → 1 commit" acceptance holds per batch unit, confirmed.

## Measure and record

- Rows/s at 10k, extrapolated to 160k.
- Commit count reduction (per-row vs per-batch).
- Index backfill behavior vs per-row path.
- Effect on `build.json` load timing.

## Not in scope

- Query path changes. Index architecture changes. CLI wrap (independent).

## H2 matrix — partial (suspended 2026-09-29 for maintenance; V4 pending)

| Variant | Load time | Rows/s | Reading |
|---|---|---|---|
| V1 full indexes | 2482s | 4.0 | Baseline at scale |
| V2 no vector | 2635s | 3.8 | +6% (noise band, not signal) |
| V3 no full-text | 2099s | 4.8 | −15%, contributor not driver |
| V4 neither | — | — | Deciding test on resume |

V1–V3 agree within family: neither index alone drives the cost. V4 decides
between "any-index triggers slow path" (defer-all-indexes) and "tx
coordination" (split tx / bulkUpsert). Suspended run killed via single-PID
SIGTERM; V4 never started. Rerun full matrix post-maintenance (V1–V3 cheap
to re-confirm, V4 is the only new datum needed — but rerun all four to
control for environment drift across the maintenance window).

## Hypothesis status

- H1 (stats): INCONCLUSIVE at small scale (2026-09-29). 100-row batch commit
  completes in ~1–2s on near-empty tables with or without NONE — the 32s cost
  does not manifest there, so stats was never exercised. Change kept
  (strictly less work). Re-run at 10k scale only if H2 fails.
- H2 (index maintenance): PRIMARY by elimination. (a) Fast at 100 rows
  (~1–2s/chunk), 32s metronomic at thousands; (b) storage-independent
  (overlayfs vs volume); (c) stats-independent at small scale. Matrix:
  indexes on/off × {vector, fulltext} at fixed 5k scale (below).

1. `setCollectStats(NONE)` on the bulk-write path: if default stats
   collection inflated the 10k response, suppressing it could raise
   TX_MAX_ROWS 5–10×. Reads unchanged.
2. BulkUpsert RPC as an alternative write path: bypasses the AST entirely
   (no VALUES parsing, no node cap). Caveat — typically outside the
   transaction model, so the "1 read + 1 commit" ordering pattern needs
   re-derivation, not direct porting. Evaluate only if per-commit cost at
   100 rows dominates (sanity rows/s will tell).

All call sites use default `ExecuteDataQuerySettings`; the SDK exposes
`setCollectStats(NONE)`. If default stats collection (FULL/PROFILE) is what
inflated the 10k response to 139MB, setting NONE on the bulk-write path
alone could raise TX_MAX_ROWS 5–10×. Try it after sanity lands; keep reads
unchanged.

## Architectural note (Phase 4 input)

024A never hit this: Lucene's index is in-JVM (B.2 load CPU-bound, no
round-trips); YDB's is client-server (network-bound, per-commit cost
exposed). Write-path batching discipline is a YDB-specific operational
concern — a material backend difference for the comparison, not a defect.
