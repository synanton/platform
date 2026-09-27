# YDB-POC-005 — Frozen Benchmark Corpus and Golden Queries (v1)

**Status:** Closed (Phase 0A; versioned, reproducible)
**Corpus version:** `ydb-poc-corpus-v1`

## Definition

| Element | v1 value |
|---|---|
| Documents (N) | 20,000 synthetic enterprise-knowledge documents |
| Chunks per document | 8 avg (160,000 chunks total), lengths 128–512 tokens |
| Embedding model + dimension | Single model for v1; **384-d** (dimension pinned here so 002/018 follow-ups have a concrete target; model id + version + digest recorded as `EmbeddingModelRef` at ingest) |
| Metadata cardinalities | `tenant` (50 tenants, Zipf-skewed), `doc_type` (8 values), `lang` (3 values), `sensitivity` (3 values), `source` (200 values) |
| Golden queries (K) | 120 (40 lexical-leaning, 40 vector-leaning, 40 hybrid) with binary relevance labels for Recall@10 (≥5 relevant chunks each, judged pre-freeze) |
| Eligibility fixtures | 3 validated `SecurityContext`s: full-access service, single-tenant user (tenant_07), cross-tenant user (tenant_07+tenant_11); plus negative fixtures for leakage tests (no-access principal, revoked delegation) |
| Filter selectivity points | 0.1% / 1% / 10% / 100% metadata filters precomputed against the corpus |

## Reproducibility

- Generation seed `42` recorded; generator script lands with YDB-POC-011 (testkit). Until then this definition is the frozen spec — numbers above must not drift without a corpus version bump (`v2`, …).
- Cassandra baseline (006) and all PoC benchmarks (022/028/032) run against exactly this corpus version.

## Open items (tracked, not blockers)

- Real-data supplement (Phase 4): a 5k-document production-sample overlay may be added as `v2` if licensing allows; v1 remains the comparison baseline regardless.
