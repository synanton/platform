# PG-POC-002 — Extension Feature Stability Inventory

**Status:** Closed (Phase 0A)
**Date:** 2026-09-27
**Depends on:** PG-POC-001 (pins)

## Inventory

| Feature | Status | Known limitations | Production-readiness |
|---|---|---|---|
| PostgreSQL 16 core (heap, B-tree, GIN, RLS, `tsvector`) | GA, supported to 2028-11-09 | None for PoC scope | Clear |
| pgvector `v0.8.6` (HNSW, IVFFlat) | Open-source, widely deployed; `0.x` versioning | Index build parameters (`m`, `ef_construction`) need per-workload tuning; dimension fixed at DDL; tie order on equal distances unverified (PG-POC-013) | PoC-valid; `0.x` line recorded as production risk for Phase 6 |
| `tsvector` + GIN (lexical fallback) | In-core, GA | BM25-equivalent ranking is custom (`ts_rank` ≠ Lucene BM25 k1=1.2/b=0.75); conjunctive/disjunctive semantics must be measured vs frozen Lucene defaults (004 matrix) | Clear (in-core, no extension risk) |
| `pg_search` (ParadeDB `v0.26.0`, BM25 candidate) | Fast-moving (`v0.26.0` latest tag); GA status **not claimed** | Upgrade compatibility across `0.x` releases not guaranteed; exact image/build recipe not yet pulled in this environment | PoC-valid only; flagged as production risk unless re-cleared at Phase 6 |

## BM25 extension decision (recorded 2026-09-27)

Default evaluation path: **in-core `tsvector`+GIN** (no extension risk; always runnable).
`pg_search` evaluated in parallel where the image is pullable; if its numbers differ,
both are reported with topology annotations (proposal §11), never silently merged.

Justification: the PoC must produce a complete four-legged comparison even if the
BM25 extension never clears production review. The fallback guarantees that.

## Re-validation procedure

- If any pin from PG-POC-001 changes: this inventory reopens, the table above is re-checked, and affected benchmark tickets (007, 010, 014) re-run the impacted legs only.
- If pgvector or `pg_search` cuts a new release mid-PoC: record the new tag, assess changelog for index-semantics changes, and decide pin-or-hold before the next benchmark run — never mid-run.
