---
title: "Demo Research"
status: "current"
last_reviewed: "2026-08-28"
---

# Demo Research

**Purpose:** Research plans and benchmark designs for evaluating platform capabilities (quality, cost, latency) before changing defaults.
**Audience:** Search engineers, ML engineers, product

## Documents

| Document | Description |
|----------|-------------|
| [`flat-vs-semantic-chunks-research-plan.md`](./flat-vs-semantic-chunks-research-plan.md) | Flat-text vs structure-aware chunking — RAG quality and cost comparison |
| [`retrieval-evaluation-benchmark-plan.md`](./retrieval-evaluation-benchmark-plan.md) | Retrieval Evaluation Benchmark (SNTP-9 / Issue #14) — BM25/vector/hybrid/graph/reranking comparison across chunking strategies and embedding models |
| [`gpu-plane-integration-tickets.md`](./gpu-plane-integration-tickets.md) | GPU Plane Integration — ticket backlog blocked on `gpu-runtime`'s GPU-5 k8s deployment; unblocks the benchmark's T02/T03 rows |
| [`hybrid-baseline-readiness-2026-10-25.md`](./hybrid-baseline-readiness-2026-10-25.md) | Hybrid baseline readiness — corpus/BM25/provisional table, dense and graded-judgment gaps |
| [`bm25-provisional-baseline-2026-10-25.md`](./bm25-provisional-baseline-2026-10-25.md) | Provisional BM25 baseline (binary judgments, bootstrap CIs) — not the official/RRF reference |
| [`grading-protocol-v1.md`](./grading-protocol-v1.md) | Blind four-level grading protocol, inter-rater plan, unjudged/depth disclosure |

## Related

| Location | Purpose |
|----------|---------|
| [`../demos/`](../demos/) | Runnable end-to-end demo scenarios |
| [`../implementation/demo/`](../implementation/demo/) | Implementation-focused demo guides |
