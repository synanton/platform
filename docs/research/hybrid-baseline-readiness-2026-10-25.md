# Hybrid baseline readiness — 2026-10-25

**Status:** Readiness audit only. No new retrieval benchmark has been run.
**Purpose:** Prevent RRF numbers from being produced on an unverified dense leg
or an undefined judgment set.

## Prerequisites

| Prerequisite | Status |
|---|---|
| Fixed corpus, hash-pinned | ✅ core `b3c4fedb457d9dc746ce723180dad76858e805e79387ebc504128a8f0d59f08e` |
| Stress corpus for guard behavior | ✅ `4869cb0907b3d6d29334d36f000a059860e9376b9f2d9b4147cfd847e28fc615` |
| BM25 leg | ✅ local real demo + #410/#413/#414 |
| Tombstone filtering with one snapshot | ✅ #410/#413/#414 |
| Dense-leg RRF symmetry gap | ❌ recorded in #414, not addressed |
| Dense leg verified **locally** | ❌ local-real-demo has `SYNQUEST_EMBEDDING_REQUIRED=false` and `VLLM_EMBED_BASE_URL=http://host.docker.internal:30801/v1`; NodePort times out, but `kubectl port-forward svc/tei-embedding-small 30801:8000` works. Actual runtime is **TEI 1.9.4, not vLLM**; model `synanton-bge-small-embedding` (dir hash `796371f6…`, image `b8deab53…`). Owner/date: #440 (@aminin; recorded 2026-10-26, work starts 2026-11-07). |
| Query set | ✅ `demo-data/eval/retrieval-benchmark/queries-v2.jsonl` (25) and `queries-f1-dense.json` (10 human-confirmed) |
| Graded judgments (highly relevant/relevant/marginal/irrelevant) | ❌ existing gold is binary; candidate pool `judgments-pool-v1.jsonl` is built but ungraded. Pool is BM25+random only (dense bias documented). |

## Decision

- **No RRF/vector baseline until the dense leg is verified locally** with the
  same per-leg snapshot and tombstone filtering used by BM25. The #414
  symmetry gap is exactly what would produce non-transferable numbers.
- **No graded nDCG claim until four-level judgments exist.** The existing
  benchmark plan measures nDCG over binary gold; that is useful, but it must be
  labelled binary-relevance nDCG, not confused with graded judgments.
- The first local benchmark is **BM25-only** on the fixed corpus. Vector-only
  and unweighted RRF (k=60) follow only after the dense leg and judgments are
  verified.

## Query set inventory

- `queries-v2.jsonl`: 25 queries (original 10 + 15 PDF-targeted). The plan
  documents the annotation mechanism (`gold_marker`/objective chunk inclusion)
  and the per-tenant remaps `queries-v2.rb-{fixed,semantic}-g5.jsonl`.
- `queries-f1-dense.json`: 10 human-confirmed queries with binary gold and
  provenance notes.
- `ragmc-queries.json`: upstream RAG-Multi-Corpus queries with gold IDs.
- **Missing:** a four-level graded judgment file. Do not infer grades from rank
  or from the current binary gold.

## Baseline table to produce (once ready)

| Arm | nDCG@10 | MRR@10 | Recall@10 | p50 / p95 latency |
|---|---|---|---|---|
| BM25-only | | | | |
| Vector-only | | | | |
| Unweighted RRF (k=60) | | | | |

Ablation after the baseline, one change at a time: fixed RRF → weighted RRF →
query-adaptive weights → result-set-adaptive weights → exact-match features →
combined. Same corpus, same judgments, compare each step to the previous.

## Dense decision rule (pre-committed)

By **2026-11-01**:

- if TEI 1.9.4 is valid/reachable → use it, pin the model by hash, populate from the
  `c799bd65…:5` ingestion path, apply the per-leg tombstone filter (#414);
- else if a pinned CPU model is ready → use it and label results **directional,
  not transferable**;
- if neither is valid → run the official BM25 baseline only, defer dense
  explicitly with a new owner/date; do not silently extend the date.
- Dense verification work starts **after grading completes on 2026-11-06** or is
  assigned to someone other than @aminin. B1 was recorded 2026-10-26; 11-01 is
  no action. TEI 1.9.4 requires an open port-forward for the full run.

## Judgment pool v1

`docs/research/judgment-pool-v1.md` and
`demo-data/eval/retrieval-benchmark/judgments-pool-v1.jsonl` contain 734
BM25-top20 + random candidates with `grade: null`. The blind grading sheet/key
and protocol are `grading-sheet-v1.jsonl` / `judgments-key-v1.jsonl` and
`docs/research/grading-protocol-v1.md`. The pool is biased against the dense
leg (no dense top-k); annotate it now, expand after #440.

## Provisional BM25 table

`docs/research/bm25-provisional-baseline-2026-10-25.md` records a provisional
BM25-only table from the existing 25-query T01 run, with bootstrap CIs and
judgment-source disclosure. It is explicitly not the official baseline and not
the RRF reference; the gaps below still block that.

## Next steps

1. Verify the vector leg locally (embedding runtime + vector storage, valid
   vector count/dimension, snapshot/tombstone parity).
2. Add four-level graded judgments for the fixed query set, or explicitly
   rename the metric to binary-relevance nDCG.
3. Run BM25-only, then vector-only, then unweighted RRF; record the table and the
   run validity checks.
