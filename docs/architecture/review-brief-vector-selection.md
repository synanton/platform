# Review brief: vector engine selection (F-4 legal, F-5 architecture)

**Status:** Signed 2026-10-11 — Andrei Minin, architecture + legal authority.
ADR-013 Accepted; framework F-4/F-5 closed. F-8 scheduling next.
**Evidence:** `vector-engine-selection.md` (framework) ·
`decisions/adr-013-vector-engine-selection.md` (Accepted) ·
`docs/implementation/benchmark-tracker.md` (F-1–F-9, B5.2–B5.6 records) ·
`demo-data/eval/retrieval-benchmark/queries-f1-dense.json` (12-query human gold).

## Decision For Legal (F-4)

1. License columns (§1a): 14 LICENSE-file reads, 2026-10-07/08. No universe
   engine carries a managed-service restriction; TSL hypothesis refuted
   (Weaviate row).
2. Elasticsearch exclusion gate (SSPL/ELv2/AGPL) —  managed-service exclusion confirmed.
3. Benchmark legality of the demo corpus (16 vendor docs +
   4 PDFs incl. a redacted SEC filing + 1 restricted employee file) for
   external publication of recall numbers.

## For Architecture (F-5)

1. Port decomposition (B1): `VectorRetriever` + sealed `upsert`, Reading-1
   (caller embeds), reuse-by-shape. Contract stands as the adapter seam.
2. Per-context defaults (ADR-013): PG+pgvector managed, Qdrant
   self-host/docker/on-prem, Lucene embedded — with revisit triggers.
3. Measured challenge to the defaults (n=12, human gold): PG/YDB recall 1.0
   vs Qdrant 0.889 — at 384d/small-model. Managed-context default hold PG's quality lead.
4. Fusion finding (F-tune): RRF structurally buries single-leg hits
   (q2 outsourcing miss, k-inert, depth-exonerated). Any fusion change is a
   SearchService review — score-based / weighting / union boost.
5. Known gaps accepted in the framework: PG/YDB 768d cells (384d lock-in,
   F-9), n=12 single annotator, Week-2 schema freeze unscheduled (F-8).

## What happens on sign-off

- ADR-013 Proposed → Accepted (deciders + date filled).
- Framework §4 F-4/F-5 rows closed; F-8 scheduled with owners.
- PG-backed synquest service (B5.4) + engine dim-parameterization (F-9)
  stay parked unless reviewers pull them forward.
