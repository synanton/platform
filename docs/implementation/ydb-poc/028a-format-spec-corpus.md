# 028a Format Spec — Corpus Half (D5/D6, Pre-Code)

**Status:** Draft for co-signature (028a implementer + PG comparator owner + YDB sign-off)
**Date:** 2026-09-27
**Branch:** `DESIGN-YDB-028a`
**Scope:** what 028a emits; consumed by 028b/c/d/e. Q3 output half is normative
by code (`synanton-bench-convergence` `RunOutput` strict parser) and is NOT
renegotiated here.

## 1. Corpus output format (JSONL, one document per line)

```json
{"doc_id": "<uuid>", "tenant_id": "tenant_07", "doc_type": "<1 of 8>",
 "lang": "<1 of 3>", "sensitivity": "<1 of 3>", "source_id": "<1 of 200>",
 "title": "<text>", "source_version_id": "<uuid>",
 "chunks": [{"chunk_id": "<uuid>", "ordinal": 0, "text": "<128-512 tokens>",
 "embedding_b64": "<base64 of 384×float32 LE>", "token_count": 123}]}
```

- 20,000 documents, 8 chunks avg (160,000 total). Counts exact, not average:
  chunk counts per doc cycle deterministically (see §4).
- `tenant_id` is a stable string id (`tenant_00`–`tenant_49`), not UUID —
  fixtures reference `tenant_07`, `tenant_11` by name (005).

## 2. Embedding format (load-bearing — see note)

- Dimension **384**, float32 little-endian, base64-encoded, L2-unit-normalized
  (cosine space — matches BaselineBench `KnnFloatVectorField` COSINE and the
  PG `vector(384)` pin; no dimension bias by construction).
- Chunk text is synthetic token salad (seeded vocab, per-doc planted terms —
  same technique as BaselineBench §005-corpus scale runs); embeddings are
  synthetic unit Gaussians **correlated with text**: chunks sharing planted
  golden terms get a shared direction component so lexical and vector relevance
  agree on the golden set. Uncorrelated noise would make vector Recall@10
  unmeasurable (the "synthetic vectors, recall unclaimed" trap).
- Query vectors (vector/hybrid golden queries): L2-normalized centroid of the
  query's ≥5 relevant chunk embeddings, base64 float32 LE, shipped in the
  query fixture — never recomputed by emitters.

## 3. Tenant distribution (50 tenants, Zipf, pinned)

- Weight of `tenant_i` (i = 0..49): `w(i) = 1 / (i+1)^s`, `s = 1.0`.
- Assignment: documents dealt in index order; doc d → smallest i with
  `cumsum(w, i) / total ≥ frac(d)`, `frac(d)` = deterministic modular
  sequence `(d * 2654435761 mod 2^32) / 2^32` (Knuth hash — no RNG state,
  reproducible in any language).
- Selectivity legs (0.1% / 1% / 10% / 100%) are tenant- OR metadata-scoped
  filters precomputed against this layout; the generator ships the exact
  eligible counts per leg for the report header.

## 4. Golden query format (120 queries)

```json
{"query_id": "q000", "mode": "lexical|vector|hybrid",
 "text": "<query text (lexical/hybrid)>",
 "query_vector_b64": "<base64 (vector/hybrid)>",
 "filter": {"kind": "none|tenant|metadata",
   "tenant_scope": ["tenant_07"], "metadata_predicate": {"doc_type": "X"}},
 "selectivity": "0.1%|1%|10%|100%|-",
 "relevant_chunk_ids": ["<≥5 ids>"],
 "eligible_chunk_ids": ["<ground truth, see §5>"]}
```

- 40 lexical-leaning / 40 vector-leaning / 40 hybrid. Lexical queries carry
  planted terms with full-match guarantee on the relevant set (conjunctive-FT
  legs compare only fully-matching queries — preflight rule).
- `relevant_chunk_ids` (recall ground truth) and `eligible_chunk_ids`
  (leakage ground truth) are distinct fields. Conflating them hides leakage
  behind recall.

## 5. Eligible-set derivation (load-bearing — see note)

Per query, by filter kind, applied to corpus metadata:

- `none` → all chunk ids.
- `tenant` → chunks whose `tenant_id ∈ tenant_scope`.
- `metadata` → chunks matching `metadata_predicate` **within** `tenant_scope`
  when present (scope always bounds; predicates never widen).

The generator precomputes `eligible_chunk_ids` and ships it in the fixture.
Emitters report the eligible set they *observed*; the comparator checks
baseline-vs-candidate identity, and each leg is additionally validated against
fixture ground truth before its Q3 is accepted (a leg disagreeing with ground
truth never reaches the comparator — it is a harness defect).

## 6. Determinism contract

- Single seed `42` drives vocab, planting, tenant dealing (via §3 hash, no
  RNG-state dependence), noise, and query construction.
- Byte-identical output: same seed → same JSONL bytes (fixed float formatting:
  base64 of exact float32 LE — no decimal rendering anywhere in the pipeline).
- Corpus version string `ydb-poc-corpus-v1` is emitted in a manifest line and
  must equal the Q3 `corpus` field or the comparator refuses the run.

## 7. Co-signature (D6 gate)

- 028a implementer (corpus half): ________________ (this branch author so far)
- PG comparator owner (Q3 half = parser, verified): ________________
- YDB workstream sign-off: ________________
- 028a implementation does not start until all three lines are filled.
