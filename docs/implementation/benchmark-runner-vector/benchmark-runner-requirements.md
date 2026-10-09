# Benchmark Runner Requirements (BR-A0.1) — DRAFT for Track A/B review

**Task:** BR-A0.1 — Gather requirements from Track B
**Status:** Draft — each section ends with open questions for the requirements session.
Nothing below is frozen until Week-2 schema freeze (BR-A0.2/A0.3/A0.4).
**Depends on:** Track A + Track B owners named (Week-1 gate closed 2026-10-01).

---

## 1. Composition description format

The manifest must describe N compositions of (metadata store × vector engine).
Known set from Track B (B5 evaluation): Cassandra+Lucene, Cassandra+Milvus,
Cassandra+Qdrant, PG+Milvus, PG+Qdrant, YDB+YDB (+ PG+pgvector reference).

Requirements on the format:
- Each composition names `metadata.provider` + `vector.provider` (VEC-B2.1 namespaces).
- Per-composition config: engine endpoints/credentials refs (not secrets inline),
  index parameters (HNSW m/ef where applicable), embedding model id + dim.
- Single-provider compositions stay zero-config (`vector.provider` defaults to
  `metadata.provider` per VEC-B2.2).

Open questions:
- Composition ID scheme: who assigns stable IDs used across manifests and results?
- Secret references: by name from which store (env, vault, k8s secret)?

## 2. Corpus reference format

- Corpus identified by content hash + `dataset_version` (no floating "latest").
- Corpus used for B5 pre-check: small/frozen subset; full corpus for B5 proper.
- Runner verifies hash before execution; mismatch aborts (BR-A1.3).

Open questions:
- Canonical corpus location (path/URI scheme) for manifests to reference?
- Who publishes the frozen v1 corpus hash, and where is it recorded?

## 3. Metric shape

Canonical metrics (BR-A0.4 taxonomy): recall@10, overlap, eligible-set identity,
latency percentiles (p50/p95/p99 + per-leg breakdown: embed/dense/lexical/fusion),
index build time, index size, freshness. Per-query topology annotation required
(no metric without its topology).

Open questions:
- Mandatory vs optional metrics for MVP (which fields may be explicitly null)?
- RAG answer-quality metric: in scope for the runner, or deferred?

## 4. Reproducibility requirements

- Corpus hash verification (abort on mismatch), seed capture, embedding model
  id + dim recorded per run, single-model-per-index invariant.
- Result Manifest carries reproducibility flags + environment record.

Open questions:
- Seed scope: query sampling only, or also index-build nondeterminism (segment merge order)?
- Environment record depth: versions of engines + runner, or full container digests?

## 5. Execution mode (parallel / sequential)

- Parallel (default): lexical + vector legs concurrently, fusion merges.
- Sequential metadata-first: lexical → eligible set → vector over candidate universe.
- Per-query override of the deployment default (VEC-B4.2).

Open questions:
- Does the manifest pin execution mode per composition, per query, or inherit deployment default?
- Crossover-selectivity measurement: runner feature or analysis-side concern?

## 6. Output needs

- N Result Manifests (one per composition) + `RunCompletedEvent` per run
  (provisional shape pending Eventing 1.27 freeze).
- Sinks: file (atomic write, required); Kafka/ClickHouse (optional, BR-A3.1).
- Result Manifest API for UI (BR-A3.2, non-blocking for B5).

Open questions:
- Result artifact layout (paths, naming) for the 6-composition B5 run?
- Event shape freeze date — who owns the Eventing 1.27 decision?

## 7. Cost controls (cross-cutting)

`--dry-run` (validate only), timeout, max-event-count guard — all
manifest-configurable, all fail loud. Approval gate for large runs: owner TBD.

## Session exit criteria

BR-A0.1 closes when every open question above has an answer recorded here and
both track owners sign off. The answers feed BR-A0.2 (manifest schema) directly.
