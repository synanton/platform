# YDB-POC-003 — Feature Stability Inventory (26.3.1.16 / SDK v2.4.11)

**Status:** Closed (Phase 0A, re-validated at Phase 6 per §13)
**Depends on:** YDB-POC-001

| YDB feature used by PoC | Status in pinned release | Production implication |
|---|---|---|
| Distributed transactions (multi-statement, multi-table commit) | GA, core YDB guarantee | None — relied upon for §9.1 atomicity |
| Row-oriented tables, composite primary keys | GA | None |
| Secondary / global indexes | GA | Async-index staleness must be measured for freshness (§15) |
| `Json` columns + JSON functions | GA | Complex-filter latency measured in Phase 2/4 |
| Full-text / lexical search | Release-sensitive; confirm exact 26.3 behavior live | ⚠️ Must-gate: feature-parity row (004); if Preview, flag as production risk |
| Vector ANN index + hybrid ranking | Release-sensitive; confirm kind/metric live | ⚠️ Must-gate: Recall@10 parity (028); if Preview, flag as production risk |
| Topic/CDC or equivalent change feed (if used for publication relay) | Confirm availability in 26.3 | ⚠️ Relay design (031) must not assume CDC until confirmed |
| Java SDK v2.4.11 (session pool, transactions, retries) | Stable upstream (`ydb-java-sdk`, active maintenance) | None known; SDK maturity recorded as adequate for PoC |

## Risk flags carried to Phase 6

1. **Server is pre-release** (001). Any Preview/Beta FTS/vector behavior found during live validation is recorded here as a production risk and appears verbatim in the Phase-6 decision package (036).
2. Re-validation at PoC end (and on any version bump) is mandatory; this file reopens on version change.
