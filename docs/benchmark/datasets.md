# Benchmark datasets registry (Phase A)

Supplements ticket SNTP-9 (#14), which defines the benchmark plan (RQ1–RQ5,
T01–T14, targets Recall@10 0.70–0.90 / NDCG@10 0.60–0.85 / p95 <150ms).
This file records dataset *provenance*: where each corpus lives, its license,
and exactly which slice the legs consume. Update on any re-download.

## RAG-Multi-Corpus (Phase A active)

- Upstream: https://github.com/udayallu/RAG-Multi-Corpus (synthetic enterprise
  content — no real/confidential info; code MIT; no formal data license, clean
  for internal reuse/relabeling).
- Local: `/home/aminin/workspace/synanton/testing/RAG-Multi-Corpus/`
  (datasets/ + bechmark/, as downloaded; do not modify in place).
- Slice consumed: `datasets/*/md/` only — 236 Markdown files across 5
  enterprises (dedupes the docx/html/pdf/pptx mirrors of identical content;
  parser ablation across formats is a later phase).
- Queries: `datasets/Dataset categories - queries_01122025.csv` — 1251 rows
  (`Enterprise Name, Query Type, Query, Supporting Facts[]` with
  `{filename, text}` gold snippets; 7 types).
- Staged ingest tree: `/tmp/stage-ragmc/<Enterprise>/*.md` (copied, never
  the upstream tree — keeps corpus hashes stable if upstream moves).
- Tenant: single `ragmc` (ticket #14 out-scopes leakage tests; per-enterprise
  tenants reserved for the classification-aware phase).
- Known gap (2026-10-11): 2/236 files never ingested
  (`ZX_Bank/Personal Loan Information.md`,
  `ZX_Bank/ZX Bank Agriculture Loan.md`) — no error logged, cause open.
  8 gold files affected → 36 queries excluded from gold (1216 scored).
  Rerun ingest picked up 0; needs a discover-walk investigation.

## PIRE (Phase B queued)

- Upstream: https://huggingface.co/datasets/Wikit/PIRE (CC BY-SA).
- Local: `/home/aminin/workspace/synanton/testing/PIRE/pdf_files.zip`
  (100 PDFs) + `bench-chunknorris-acl2025/` harness (Apache-2.0 code).
- 332 queries (chunk.single 300 + chunk.multi 32), file+page+passage gold.

## OHR-Bench (Phase C queued)

- Upstream: https://huggingface.co/datasets/opendatalab/OHR-Bench
  (CC-BY-4.0, research-only, non-commercial).
- Local: `/home/aminin/workspace/synanton/testing/OHR-Bench/` (8.5k pages,
  8.5k QAs, page-level gold; 3.3GB).

## Demo corpus (F-1–F-3 basis, retained)

- `demo-data/documents` (16 docs) + `demo-data/eval/retrieval-benchmark/`
  (`queries-f1-dense.json`, 28 human-confirmed queries). Stays as the
  smoke/regression corpus; RAG-Multi-Corpus becomes the quality corpus.
