# PG-POC-018 (Draft) — PG-Leg Q3 Emitter

**Status:** Draft against Q3 half (normative via `RunOutput` strict parser); corpus-half integration waits on 028a spec
**Date:** 2026-09-27
**Decisions:** D3 (PG owns PG-leg emitter), D5/D6 (format spec first)

## Q3 half — already normative, no negotiation

Output must parse under `synanton-bench-convergence` `RunOutput` today:

- Top level: `run_id`, `corpus` (`ydb-poc-corpus-v1`), `queries[]`.
- Per query: `query_id`, `mode` (lexical/vector/hybrid), `filter`
  (none/eligibility/metadata), `selectivity`, `top_k[]` (`chunk_id`, `score`,
  `rank`), `eligible_set[]`, `timing_ms`.
- Strictness is the contract: any field the emitter adds beyond this is
  ignored; any field missing fails the run with a filename. Draft the emitter
  by round-tripping fixtures through the parser before touching PG.

## Emitter shape (planned)

- Input: v1 corpus loaded in PG tables (via Phase 5 migrator path once
  `PostgresSynvaultStore` exists) + 120 golden queries from the corpus spec.
- Execution: each query through `PostgresSynquestEngine` per its mode/filter;
  collect top-K ids, eligible ids, per-query wall timing.
- Metric names follow the frozen rules: `vec_p95_btree_sort` until HNSW
  engages (005-gate-a.md), `*_rls_native` / `*_predicate_composed` suffixes on
  eligibility legs, post-retrieval `(score desc, chunkId asc)` sort on every
  query (013).
- Determinism: fixed query order, frozen corpus, recorded seed — same seed →
  same JSON modulo tie-order (tolerance-based, never exact-sequence).

## Q3 obligations from 007-4 (minScore + fusion internals)

Score thresholds are not portable across backends (dev-guide rule): each
query record carries `min_score` per leg per backend, in that backend's own
score space (BM25-unbounded vs `ts_rank` vs `-cosine_distance` vs
`rrf/maxLex`). A shared cutoff would compare different filters. When the
Phase-4 internal-parity follow-up lands, add `rrf_k`,
`fusion_input_size_lexical`, `fusion_input_size_vector`, `score_semantics`
per hybrid query; until then the parity mode stays behavioral (top-K
comparison), stated in the run record.

## 028e run rules (pre-flags, recorded before the run)

- Fan-out semantics: the PG run routes every query through the shared
  `QueryExecutor` (never a bespoke loop) — one tenant scope per call, one
  RLS-scoped session per call, timing summed. Filtered legs report
  `timing_scope: summed_fanout_N` from the executor; a `single` scope on a
  filtered leg means RLS was bypassed — fail loudly rather than emit.
  (PG is stronger here than YDB by construction: the engine opens a fresh
  connection + `SET LOCAL` per operation, so no unscoped session path
  exists in code. The rule guards the harness, not the engine.)
- Score-space annotation: `min_score` per query reflects the value the
  engine applied (frozen neutral 0.0 via `QueryExecutor`), not a default
  filled at serialization. Backend-local per the 007-4 finding.
- Mount gate (CLI.1): `./scripts/verify-mounts.sh <pg-container>
  /var/lib/postgresql/data` before load begins — the pgvector/pg16 data
  path, not YDB's. Abort on failure, never run on overlayfs.

## Corpus-half placeholders (blocked on 028a spec)

- **Embedding format:** dimension + encoding per query/corpus. If 028a ships a
  dimension different from the 384-d the PG schema pins (`vector(384)`), the
  dimension-bias annotation applies and the mismatch is resolved before
  integration, not during the run.
- **Eligible-set derivation:** tenant filter, metadata filter, or both — per
  query. The adversarial leakage legs need this as ground truth; without it
  the emitter cannot distinguish "eligible" from "returned."

## Note on 028c (024A emitter, PG-owned per D2)

Same Q3 half applies. 028c drives the 024A adapter (Cassandra port over
ingestion-cache/Lucene) instead of PG. Shared emission code with 018 where
the harness allows; divergence recorded, never silently merged. Blocked on the
same corpus-half spec.

## Unblock conditions

- Draft → implementation: corpus-half spec signed (D6 gate).
- Implementation → integration: 028a output available + PG Phase 2 open (R4).
