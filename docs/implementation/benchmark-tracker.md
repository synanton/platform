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
| 006 re-freeze (thresholds from baseline-v1.json) | andreminin | Unblocked now — runs parallel with 041, not serial after C.2 |
| 024B/PG fusion-semantics check (pre-ranking invariant) | YDB workstream (024B) · PG workstream (PG) | **024B CLOSED**: iterative over-fetch on all 3 legs (page-until-full, 20k cap, fail-loudly `SCAN_CAP_HIT` tripwire); regression + cap tests green. PG still deferred to leg activation |

Cross-cutting:

| Component | Ticket | Status |
|---|---|---|
| Emitter module (shared) | BENCH-EMIT-* (was A.1–A.6) | Phase A complete, green |
| Comparator | BENCH-CMP (was PG-POC-017) | Green (12/12 + 4/4 contract) |
| Corpus generator | 028a.1–028a.11 | Complete on main |
| Phase 4 backlog | — | NONE experiment, index isolation, BulkUpsert-RPC, clock skew, resource broker |

## Alias map (transition only — drop after all branches renamed)

- 028b → BENCH-BASE · 028c → CASS-EMIT-024a · 028d → YDB-EMIT-024b ·
  028e → PG-EMIT-pg · A.* → BENCH-EMIT-* · PG-POC-017 → BENCH-CMP
- runs/024a-v1.json → runs/cassandra-v1.json · runs/024b-v1.json → runs/ydb-v1.json
- legs [baseline, 024A, 024B, PG] → [baseline, cassandra, ydb, postgres]

## Rename order

1. B.2 completes as-is; artifact renamed to runs/cassandra-v1.json at CLI-wrap.
2. Branch + config + tracker rename pass before 024b starts.
3. Baseline/PG rename on next touch.

## Parked (single source — lands only here, not in branch-local files)

- `--engine baseline` wiring + README boot-jar line: parked until 028b line
  and emitter line both merge to main; then one tiny PR off main.
  (Two branch-local copies would drift — this section is the only record.)
