# Judgment pool v1 — candidate set, not grades

**Status:** Candidate pool built 2026-10-25. No graded labels yet.
**Protocol:** [grading-protocol-v1.md](./grading-protocol-v1.md) — blind sheet and
key are `grading-sheet-v1.jsonl` / `judgments-key-v1.jsonl`.
**Builder:** `tools/retrieval-eval/build_judgment_pool.py`
**Output:** `demo-data/eval/retrieval-benchmark/judgments-pool-v1.jsonl` (734 rows,
25 queries).

## Method

For each query in `queries-v2.rb-fixed-g5.jsonl`:

1. take BM25 top-20 from the T01-q25 hits;
2. add 10 deterministic random chunks from the 81-chunk corpus (seed derived
   from the query id), excluding the top-20;
3. write one row per candidate with `grade: null`, `judged: false`, and the
   binary gold flag for reference only.

Some queries returned fewer than 20 hits; the pool contains 734 candidates, not
750.

## Bias (must be carried into the acceptance)

- **No dense top-k.** The pool was built before #440; documents only the dense
  leg would retrieve were never judged. An RRF comparison against this pool is
  biased **against** the dense leg. State that in the results, not just here.
- The random sample is from the same 81 chunks BM25 retrieved somewhere; it is
  not an independent corpus sample. Expand the pool with dense top-k and a
  fresh random sample once the dense runtime exists.
- `gold_binary` is the existing binary judgment, not a four-level grade.

## Grade schema

| grade | meaning |
|---:|---|
| 3 | highly relevant |
| 2 | relevant |
| 1 | marginal |
| 0 | irrelevant |

Set `judged: true` when a human assigns a grade. Do not infer grades from
`gold_binary`, BM25 rank, or dense rank.

## What this enables / does not enable

- Enables annotation to start now and makes the prompt/coverage explicit.
- `rb009` is a negative query and is excluded from the nDCG/MRR/Recall
  aggregate; it is reported separately.
- Does **not** enable the official BM25 baseline: no four-level labels exist.
- Does **not** make RRF numbers transferable: no dense pool yet.

## Next

1. Annotate the 734 candidates (or a prioritized subset) with the four-level
   schema; preserve the human/source provenance.
2. Add dense top-k once #440 is decided, then re-pool and re-annotate the new
   candidates.
3. Official BM25 under graded judgments, then RRF (k=60) and the ablation.
