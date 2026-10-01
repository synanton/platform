# PG-POC-007-4 — Fusion Parity Probe

**Status:** PROBED 2026-09-30 (parameter axes decided; behavioral axes pending 007-5 overlap runs).
**Mode: behavioral parity only.** The 024B Q3 artifact records
`query_id, mode, filter, selectivity, top_k, eligible_set, timing_ms,
timing_scope` — it does not carry RRF k, fusion input sizes, or score
semantics. Parameter axes below are verified against YDB **code**
(`CassandraSynquestEngine`, which mirrors the production service), not the
artifact. Internal parity (artifact-carried fusion fields for both legs) is
a follow-up ticket if Phase 4 needs it — this probe states its mode so the
result stays interpretable.

## Axes (two kinds — not five independent tests)

Parameter-level axes are directly checkable; their results are named
values. Behavioral axes are diagnostic labels for overlap drops at 007-5,
not independent measurements.

| Axis | Kind | PG | YDB (code) | Verdict |
|---|---|---|---|---|
| RRF k | parameter | 60 (decision, implemented 007-5) | 60, `1/(60+rank+1)` per list | MATCHING (by decision) |
| Fusion inputs | parameter | top-100 per leg (decision, 007-5) | top-100 lexical + top-100 dense | MATCHING (by decision) |
| Hybrid score | parameter | `rrf / maxLexicalScore` (decision, 007-5) | `rrf / max`, max over lexical TopDocs (floor 1e-9) | MATCHING shape — ordering identical to raw RRF either way (query-constant divisor); magnitudes differ, see score space |
| Tie ordering | parameter | `score desc, chunkId asc` (013, all modes) | `score desc, chunkId asc` (final sort) | MATCHING — genuinely, not imposed: both engines arrived at the same deterministic sort independently; the probe recorded the match, it did not set PG's behavior to match |
| Filter application point | parameter (mechanism, not outcome) | pre-retrieval in SQL (`WHERE` + `@>`) | post-fetch in Java (`matches()` after top-100 fetch, all modes) | MECHANISM difference, outcome-convergent: with YDB's iterative over-fetch fix (page-until-topK-eligible), both compute "topK of the filtered set" — different paths, same endpoint, converging per R3's eligible-set hard gate. Exception if over-fetch trips `SCAN_CAP_HIT` (20k cap): then YDB fails loudly rather than converging — a halt, not a silent divergence. 007-6 guards PG's pre-retrieval side (EXPLAIN + binding behavioral legs); see its ticket cross-ref. |
| Score space | parameter, per-leg scoped | lexical `ts_rank` (custom, ≥0); vector `-cosine_distance` (∈ [-2, 0]) | lexical BM25 (unbounded); vector cosine similarity | DIVERGENT BY CONSTRUCTION — ranking-relevant only through order; RRF is rank-based so fusion order is immune; `minScore` thresholds are NOT portable across backends |
| Term matching | behavioral | disjunctive `to_tsquery` (007-2, pending 007-4 axis check → this row) | Lucene QueryParser default OR | PENDING — verified by overlap at 007-5; divergence shows as candidate-set difference |
| Lexical semantics | behavioral | `ts_rank` vs BM25 | BM25 k1=1.2/b=0.75 | PENDING — shows as overlap drop at 007-5, same class as YDB's FulltextScore finding |

## Scope constraint (score space)

The score-space axis applies to per-leg `top_k[*].score` fields only.
RRF operates on rank, so fusion-level parity is unaffected by score-space
divergence. A hybrid-level score difference is not a problem unless the
hybrid *ordering* diverges.

## Decisions for 007-5 (binding)

- RRF k=60, formula `1/(60+rank+1)` per list, top-100 inputs per leg,
  hybrid score `rrf/maxLexicalScore` — mirror YDB shape exactly.
- `minScore` semantics: per-leg on raw leg scores, hybrid on the normalized
  score — same application points as YDB, non-portable thresholds.
- Machine-readable twin: `007-4-fusion-parity.json` (mode + axes). The
  `build.json` fusion fields (`rrf_k`, `fusion_input_size_*`,
  `score_semantics`) land with the 028e emitter as a follow-up obligation,
  not here — this probe has no bench run to attach them to.

## 028e addendum — ANN session shape (neither cause fundamental)

R3's PG vector gap (0.23 vs 0.83) resolved into two addressable causes
(pgvector ≥ 0.8.0; we're on 0.8.6):
(a) pre-0.8.0 overfiltering behavior — fixed by `iterative_scan`
(`strict_order`: exact distance ordering for tie-break + RRF; the
planner's btree-vs-ANN choice at 007-3 was intended 0.8.0 cost behavior,
not misconfiguration);
(b) `probes=1` default floor — raised to 10 (10% of 100 lists).
Both set per operation via `SET LOCAL` (same lifecycle as the tenant
claim); values single-sourced as engine constants and recorded in
`build.json` (`ann_session`). Expected per literature: filtered recall
~50–70% → ~99% on tenant legs, selectivity-dependent (large at 0.1%/1%,
negligible at 100% — the rerun's per-selectivity breakdown is the check).
If the gap closes, the Outcome-8 justification weakens accordingly —
recorded here so the inference updates with the number.
