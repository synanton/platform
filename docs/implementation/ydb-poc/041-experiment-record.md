# 041 Experiment Record (paused 2026-09-29)

- Branch: `DESIGN-YDB-041-bulk-upsert` (pushed, tree clean)
- Last completed: progress-file truncation; CLI/tracker updates
- State at pause: sanity at 28/100 chunks, ~33s/chunk metronomic; killed via
  single-PID SIGTERM (mid-commit chunk rolls back by single-tx design)

## Measured findings

- Per-row path: ~4.3 rows/s (~70ms/commit, 3 commits/row)
- Batch path (100-row chunks): ~32s/commit metronomic (32.4, 34.5, 29.9…),
  ~2.7 rows/s — SLOWER than per-row. Fixed per-commit cost dominates.
- Superlinear: commit cost grows faster than row count (70ms×1 row vs
  32s×100 rows ≈ 450× cost for 100× rows)
- Storage-independent: same ~32s on overlayfs and volume-mounted storage
- Idle CPU: 104% pre-volume-mount → 2.8% post (burn was overlayfs +
  residual compaction; commit cost was not)

## Storage fix applied and confirmed

- `/ydb_data` volume-mounted to `~/ydbd/ydb_data` (+ certs volume, pinned tag,
  flags re-applied, CA re-copied)
- Overlayfs hypothesis falsified for commit cost; confirmed for idle burn

## Deferred to Phase 4

- NONE experiment (setCollectStats) — 30 min, prime suspect for the 32s
- Index-maintenance isolation (bulk write without vector index)
- BulkUpsert-RPC follow-up #2
- Clock skew check
- Resource broker / actor system config review

## Not blocked

C.2 can run at ~14 hr wall time with current code (100 chunks × ~35s + queries).

## Resume instruction

Volume-mounted container is already correct. Run the NONE experiment first
(30 min, cheap). If per-commit collapses, proceed to full C.2. If not, run
index-isolation. Do not start full C.2 until either (a) NONE succeeds or
(b) ~14 hr wall time is explicitly accepted.
