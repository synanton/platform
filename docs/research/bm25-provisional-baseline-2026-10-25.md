# BM25 provisional baseline — 2026-10-25

> **Superseded corpus (2026-10-26).** The 81-chunk `rb-fixed-g5` corpus predates the intentional hierarchical chunking commit `28c20b1` (2026-09-25T19:20Z; benchmark run 18:57Z). Current `main` produces 317 chunks for the same documents. This table measures superseded corpus behavior and is **not comparable** to B4 on current chunking. Do not use it as the RRF reference; it is retained as historical evidence only.

**Status:** Provisional. Not the official baseline, not the RRF reference.
**Source run:** `demo-data/eval/retrieval-benchmark/results/T01-q25` (2026-09-25),
BM25-only, fixed chunking, tenant `rb-fixed-g5`, 25 queries.
**Judgments:** binary gold, not four-level graded.
**Latency:** historical run (p50 6.6 ms / p95 11.8 ms); not a local-real-demo
measurement.

## Why provisional

The 25-query set is 10 human-confirmed queries (`rb001`–`rb010`, including the
negative `rb009`) plus 15 PDF queries judged by objective author markers
(`gold_source` + `gold_marker`). That is sufficient for a first BM25 reference,
but it is **not comparable** to a future RRF table under four-level graded
judgments. Do not treat this number as the reference RRF must beat.

## Results (binary relevance)

| Set | n | nDCG@10 [95% CI] | MRR@10 [95% CI] | Recall@10 [95% CI] |
|---|---:|---|---|---|
| All 25 | 25 | 0.824 [0.707, 0.923] | 0.786 [0.646, 0.907] | 0.960 [0.880, 1.000] |
| Human-confirmed 10 | 10 | 0.756 [0.526, 0.950] | 0.714 [0.464, 0.950] | 0.900 [0.700, 1.000] |
| PDF marker 15 | 15 | 0.869 [0.751, 0.970] | 0.833 [0.678, 0.967] | 1.000 [1.000, 1.000] |

Bootstrap: 20,000 resamples over queries, seed 42, 2.5/97.5 percentiles.
`rb009` is a negative query with no gold chunks, so it contributes zero recall;
the human-10 recall is capped at 0.9 by construction.

## Judgment-source disclosure

| Source | Queries | Status |
|---|---|---|
| Human-confirmed | `rb001`–`rb010` | Binary gold, remapped by `(source_uri, chunk_ordinal)` with matching chunk SHA; single annotator, no adjudication |
| PDF markers | `pdf001`–`pdf015` | Objective `gold_source` + `gold_marker` matching; author-defined markers, not four-level grades |

No four-level (highly relevant / relevant / marginal / irrelevant) judgments
exist yet. The labels above are binary.

## CI width and query count

The all-25 nDCG CI width is **0.216**; the human-10 width is **0.424**. With this
many queries, most ranking changes will look statistically indistinguishable.
Report CI width with every future comparison and expand toward 50–100 queries
before the adaptive-fusion ablation, where differences are smaller.

## What this does not prove

- Dense/vector leg: not run; local-real-demo embedding is disabled and the dense
  leg is not verified locally.
- RRF: not run; #414's per-leg snapshot/filter symmetry is not implemented.
- Graded nDCG: not available; do not call this graded nDCG.

## Next

1. Confirm all 25 judgment sources or restrict the official baseline to the 10
   confirmed queries.
2. Add four-level graded judgments with TREC-style pooling (BM25 top-k + vector
   top-k once available + random sample).
3. Verify the dense leg locally (runtime/model pin, storage/filter semantics,
   ingestion-path index population); this also closes #414.
4. Official BM25 under graded judgments, then RRF (k=60) and the ablation.

The benchmark caught an intentional chunking change that invalidated a same-evening snapshot; the recovery is #467.
