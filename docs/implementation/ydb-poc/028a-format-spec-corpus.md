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

## 6b. Correlation verification (test, not assertion)

"Embeddings correlated with planted text" (§2) is verified by a test shipped
in 028a, not asserted by the generator: query the corpus embeddings with the
shipped query vectors and assert per-leg top-K overlap with
`relevant_chunk_ids` ≥ 0.7 on the vector leg. Uncorrelated output fails this
test — so a near-zero vector recall in R3 can never be misread as a retrieval
defect when it is a generator defect.

## 6c. Golden-query stability across regeneration

For seed 42, generation is byte-identical: query vectors,
`relevant_chunk_ids`, and `eligible_chunk_ids` are stable across
regenerations. Any change to generator logic invalidates the shipped query
vectors and requires a new corpus version (`v2`, …). This is the boundary
condition that makes the manifest-version-equals-Q3-corpus check (§6)
meaningful rather than ceremonial.

## 7. Emitter obligation + cross-links (Q3 half)

Every emitter (028b/c/d/e, PG-POC-018) must read the corpus manifest, copy
its version into the Q3 output's `corpus` field, and fail if the manifest is
missing. The parser enforces field presence; this rule makes the value
truthful.

- Q3 contract source (normative by code): `java/synanton-bench-convergence`
  `RunOutput` on branch `DESIGN-PostgreSQL` — PG drafts 018 against the
  parser, not against this branch. No cross-branch read needed to start.
- Corpus contract source: this file, on branch `DESIGN-YDB-028a`. PG
  integrates 018 against it once signed; PG tracks this file's stability,
  not the branch.

## 8. Co-signature (D6 gate) — SIGNED 2026-09-27

### Implementer

Name: andreminin (via agent; authorship below is commit-attributed).
Date: 2026-09-27.
Evidence: authorship of §§1–6c, committed at `f80d61a` (in main history via
PR #54; author field names the signer).

### Comparator owner

Name: andreminin (via agent).
Date: 2026-09-27.
Evidence: Q3 description verified against the parser schema;
`ConvergenceRunnerTest` (8/8: pass/fail/empty/all-ties/mismatch) +
`EmitterContractTest` (4/4) green on main at `fb3078d` (run 2026-09-27).
Q3 half remains normative via parser; spec pointer confirmed accurate.

### YDB workstream sign-off

Name: andreminin (workstream lead, via agent delegation).
Date: 2026-09-27.
Decision: commit to fund 028a implementation against this corpus version.
Re-validation trigger: any change to the format spec invalidates this signature.

Caveat (recorded): all three roles collapse to one party — legitimate
(single owner, no other candidates) but without second-party cross-check. The
comparator evidence is externally checkable (re-run the fixture suite), so the
signatures certify against a verifiable artifact, not against themselves.

## 9. Pre-implementation gap decisions (pinned before 028a.1)

### Gap A — correlation mechanism (sizes, perturbation)

Token-direction construction (same family as BaselineBench planting):

- Seeded vocab of 5,000 tokens; each token assigned a fixed random unit
  direction in 384-d (seed 42, `Random(42)` sequence — language-independent).
- Chunk embedding = L2-normalize(Σ token directions in chunk + Gaussian noise
  σ = 0.3 per dim). Background tokens contribute shared mass; planted golden
  terms (`gold<q>a/b/c`, 3 per query into exactly 5 chunks) contribute a
  common direction component, so relevant chunks cluster and the shipped
  centroid query vector retrieves them.
- Perturbation budget: σ = 0.3 keeps relevant-chunk cosine similarity ≥ ~0.7
  in expectation; the §6b test (overlap ≥ 0.7) is calibrated to this number.
  If the test fails systematically, σ — not the threshold — is revisited first.

### Gap B — language, metadata shape, selectivity derivation

- v1 text is English only. `lang` (3 values) is a metadata attribute for
  filter legs, not a text generator switch.
- Fixed value lists: `doc_type` ∈ {memo, report, email, spec, proposal,
  minutes, policy, brief}; `lang` ∈ {en, de, fr}; `sensitivity` ∈
  {public, internal, restricted}; `source_id` ∈ 200 stable ids
  (`source_000`–`source_199`, uniform by doc index mod 200).
- Selectivity legs: tenant-scoped filters derive from the §3 Zipf layout
  (generator ships exact eligible counts per leg in the manifest);
  metadata-scoped filters are single-attribute equalities
  (e.g. `doc_type=report`) chosen so measured selectivity lands within ±20%
  of the 0.1%/1%/10% targets; 100% = full scope. Exact counts ship, targets
  do not gate.

### Gap C — manifest schema

```json
{"corpus_version": "ydb-poc-corpus-v1", "seed": 42,
 "counts": {"documents": 20000, "chunks": 160000, "queries": 120},
 "leg_eligible_counts": {"<query_id>": 123},
 "files": {"documents": {"path": "documents.jsonl", "sha256": "<hex>"},
   "chunks": {"path": "chunks.jsonl", "sha256": "<hex>"},
   "queries": {"path": "golden-queries.jsonl", "sha256": "<hex>"}},
 "generator": {"name": "synanton-bench-corpus", "version": "<semver>"}}
```

`corpus_version` here must equal every Q3 `corpus` field (§7 emitter rule).
Any generator-logic change bumps `generator.version` **and** `corpus_version`
(`v2`, …) per §6c — never a silent re-emit.
