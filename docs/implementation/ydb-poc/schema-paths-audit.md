# Schema-constructing paths audit (2026-09-26)

Every schema-constructing path checked for the silent-omission shape
(succeeds-green while constructing nothing).

| Path | Mechanism | Failure mode | Verdict |
|---|---|---|---|
| `SchemaInstaller` (ingestion-cache, Cassandra CQL) | Throws per statement? No — warns and continues | Silent skip (V7 proven case) | Guarded by `SchemaInventoryTest` (8 tables asserted) |
| `YdbSchema` (vault, main) | Throws on `!isSuccess` | None — fail-fast | Covered (any DDL failure fails the test run) |
| `YdbSearchSchema` (quest, main) | Throws on `!isSuccess` + `interpret()` names the flag | None at DDL level | Covered; async index *build* state covered separately by PlanAssertions usage tests |
| Index *build* completion (FT/vector, async) | Not asserted by DDL success | Queries fail or silently under-recall until build completes | Usage tests assert resolution; benchmark harness must warm/wait (see preflight empty-table rule) |
| Test scratch probes (`Ydb*Probe`, `YdbHybridDiag`) | Ad-hoc DDL, gated `-Dydb.probe` | Drift from canonical schema | Accepted (throwaway diagnostics, never block CI); canonical schemas live in main |
| Migration tooling | Now delegates to `YdbSchema` (was inline copy — fixed when relay copy drifted) | Copy drift | Fixed structurally; no inline DDL remains |

Rule going forward: schema DDL lives in exactly one place per backend (main
sources); tests call it, never copy it.
