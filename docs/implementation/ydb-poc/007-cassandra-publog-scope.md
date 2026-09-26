# YDB-POC-007 — Cassandra Publication-Log Track (Scope and Estimate)

**Status:** Closed (Phase 0A) — separate track per Decision 6
**Owner:** TBD (assign before Phase 0B)

## Work breakdown

| # | Item | Estimate |
|---|---|---|
| 1 | Baseline current Cassandra write path (no publication record): throughput, latency, failure modes | 3 d |
| 2 | Design `publication_log` equivalent for the Cassandra adapter (table + relay), consistent with §12.2 tenant-scoped `pending()` | 3 d |
| 3 | Implement relay client against Eventing 1.27 (provisional until 1.27 frozen — see 010) | 5 d |
| 4 | Atomicity gap analysis (feeds 008): can Cassandra satisfy §9.1, or is an exception required? | 2 d |
| 5 | Idempotency / replay / rebuild tests for the Cassandra relay | 3 d |
| **Total** | | **~16 d (one engineer)** |

## Schedule and decoupling

- Runs in parallel with Phase 0B/1; **YDB PoC success does not depend on it** (§12.2 scope note).
- Current Cassandra behavior must be baselined (item 1) before any relay change lands.
- If Eventing 1.27 is unfrozen (010 = throwaway), item 3 targets the provisional event schema and is re-done at 1.27 freeze — estimated separately, not hidden in this track.
