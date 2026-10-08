# Track B — SYN-VECTOR-001 (VEC · 24 tasks)

Parent: [INDEX.md](./INDEX.md)

Canonical 24 (folded from 35 listed). B0 8→5, B1 5→4, B2 4→3, B3 5→4, B4 3→2, B5 6→4, B6 4→2.
Old-ID mapping noted per task. Conditional 3rd-party adapter parked, not counted.

Historical IDs from the pre-reconciliation draft are noted inline where tasks were merged;
canonical IDs in this file are current. See INDEX.md for the complete reconciliation table.

> **Principle: cross-runtime boundaries are service boundaries.** Any non-JVM component
> integrated into the platform runs as a separate process or service. JNI and native-library
> embedding are explicitly out of scope — they create build-toolchain coupling, JVM crash risk,
> and cross-language memory coordination that outweigh their latency benefit. See dev-guide rule:
> "Cross-runtime boundaries are services, not JNI." (Added 2026-10-01, Rust-as-service update.)

## Phase B0 — Document-driven comparison (Week 1–2)

### VEC-B0.1 — License matrix (old B0.1)

- Description: SPDX identifier, copyleft scope, SSPL/AGPL flags, commercial-use restrictions
  for all engines on primary + adjacent lists.
- Acceptance: Every cell filled; commercial restrictions named.
- Evidence: `vector-license-matrix.md` on Track B branch.
- Estimate: 2 days.
- Depends on: Track B owner named.

Matrix seed — Tantivy rows (added 2026-10-01; two rows, not one):

| Engine | License | Copyleft scope | Notes |
|---|---|---|---|
| Tantivy | MIT | Permissive | Library; service requires wrapper |
| Quickwit (Tantivy-based) | Apache 2.0 | Permissive | Distributed service built on Tantivy |

### VEC-B0.2 — Deployment-mode + managed-availability matrix (merges old B0.2 + B0.6)

- Description: Which engines support cloud-managed / cloud-self / on-prem / docker / embedded,
  and which managed offerings exist on which clouds.
- Acceptance: Every engine × context and engine × cloud cell filled.
- Evidence: `vector-deployment-modes.md` (incl. managed section).
- Estimate: 1.5 days.
- Depends on: VEC-B0.1.

Matrix seed — "Runtime integration" column (added 2026-10-01). Rule: every cell filled;
"External service" is a first-class value, not a fallback:

| Engine | Runtime integration | Process model |
|---|---|---|
| PostgreSQL + pgvector | In-process (SQL extension) | Same host as PG |
| YDB | In-process (native index) | Same host as YDB |
| Cassandra + Lucene | In-process (JVM library) | Same host as Cassandra |
| Milvus | External service | Separate process/cluster |
| Qdrant | External service | Separate process/cluster |
| Tantivy | External service | Sidecar or Quickwit |
| Lucene (standalone) | In-process (JVM library) | Same host |
| Weaviate | External service | Separate process/cluster |
| Vespa | External service | Separate process/cluster |
| OpenSearch | External service | Separate process/cluster |

Matrix seed — managed-availability rows for Tantivy/Quickwit (old B0.6 scope, merged here):

| Engine | Cloud-managed | Cloud self-hosted | On-prem | Docker | Embedded |
|---|---|---|---|---|---|
| Tantivy (sidecar) | N/A | ✅ | ✅ | ✅ | ⚠️ (embedded Rust binary, not JVM-embedded) |
| Quickwit | ⚠️ (via managed Rust services) | ✅ | ✅ | ✅ | ❌ |

"Embedded" for Tantivy-sidecar means co-located with the service, not in-process —
the distinction matters and is stated, not implied.

### VEC-B0.3 — Hardware + scalability + operational complexity (merges old B0.3 + B0.4 + B0.5)

- Description: CPU profile, memory footprint per 1M vectors at 384/768/1536d, storage footprint;
  vertical max, horizontal model, replication, consistency, upgrade path; node count for HA,
  dependencies, backup, observability, failure modes.
- Acceptance: Every engine has an entry; each hardware cell marked measured / sourced / assumed
  (assumed → Phase-5 measurement candidates).
- Evidence: `vector-hardware-profiles.md` (incl. scalability + ops sections).
- Estimate: 3 days.
- Depends on: VEC-B0.1.

Matrix seed — Tantivy/Quickwit hardware rows (added 2026-10-01; no assumed values —
every cell measured, sourced, or flagged):

| Engine | CPU profile | Memory footprint | Storage footprint | Status |
|---|---|---|---|---|
| Tantivy (sidecar) | Unmeasured; Rust, no GC — expected lower tail latency | Unmeasured at 384/768/1536d | Unmeasured | ⚠️ Phase-5 candidate |
| Quickwit | Unmeasured; multi-process distributed | Unmeasured | Unmeasured; decoupled storage | ⚠️ Phase-5 candidate |

Matrix seed — operational complexity rows (old B0.5 scope, merged here).
Explicit counts, not qualitative labels:

| Engine | Process count for HA | Dependencies | Backup model |
|---|---|---|---|
| Tantivy (sidecar) | 2+ per host (JVM + sidecar) | Rust toolchain at build; binary at deploy | Snapshot the sidecar's index dirs |
| Quickwit | N searchers + M indexers + coordinator | Rust toolchain at build; cluster config | Native cluster snapshots; S3-backed |

### VEC-B0.4 — Per-context profiles (old B0.7)

- Description: Five profiles (cloud-managed, cloud-self, on-prem, docker, embedded). Each:
  disqualified engines, viable engines, recommended default, trade-offs.
- Acceptance: Every context names a default, not hedged.
- Evidence: `vector-context-profiles.md`.
- Estimate: 2 days.
- Depends on: VEC-B0.2, VEC-B0.3.

### VEC-B0.5 — Cross-context recommendations (old B0.8)

- Description: Summary table: context → recommended / alternative / notes.
- Acceptance: Every context has a recommendation.
- Evidence: Section in `vector-context-profiles.md`.
- Estimate: 4 hr.
- Depends on: VEC-B0.4.

Gate B0: Decision matrix (non-functional) drafted. Cross-context recommendations named.
Informs BR-A0 schema design.

## Phase B1 — Port decomposition (Week 3–5)

### VEC-B1.1 — VectorRetriever port + DTOs (merges old B1.1 + B1.2)

- Description: Define `VectorRetriever` in `synquest-api`: `search(SecurityContext,
  VectorSearchRequest)`, `capabilities()`. Narrow DTOs: `VectorSearchRequest`,
  `VectorSearchResult` (reuses/subtypes `SearchResult` cleanly), `VectorProjection`.
  Scope is vector-only, no lexical.
- Acceptance: Port + DTOs defined; parser round-trip test green.
- Evidence: Interface + DTO files + tests.
- Estimate: 1 day.
- Depends on: Track B owner named.

Clarification (2026-10-01, typo fixed 2026-10-08 — `LexicalRetriever` → `VectorRetriever`;
no such sibling port exists in `synquest-api`): implementation options for non-JVM engines —
`VectorRetriever`
implementations that wrap external services (HTTP, gRPC, or Unix socket) follow the same
contract as in-process implementations. The port is transport-agnostic. No new task.

### VEC-B1.2 — VectorIndexWriter port (old B1.3)

- Description: `upsert(List<VectorProjection>)`, `delete(GenerationId, Collection<ChunkId>)`,
  `rebuild(RebuildOptions)`; ordering-key + generation semantics documented.
- Acceptance: Port defined; semantics documented.
- Evidence: Interface + javadoc.
- Estimate: 4 hr.
- Depends on: VEC-B1.1.

### VEC-B1.3 — Wrap existing adapters as VectorRetriever (old B1.4)

- Description: Cassandra+Lucene, YDB, PG+pgvector each expose their vector path through
  `VectorRetriever`. No behavior change.
- Acceptance: All existing contract tests green; facade returns identical results.
- Evidence: Test suite unchanged + green.
- Estimate: 2 days.
- Depends on: VEC-B1.1, VEC-B1.2.

### VEC-B1.4 — Facade refactor (old B1.5)

- Description: `SynquestEngine` facade routes vector mode through `VectorRetriever`; lexical unchanged.
- Acceptance: Zero external behavior change.
- Evidence: All existing tests green.
- Estimate: 2 days.
- Depends on: VEC-B1.3.

Gate B1: Pure refactor. Zero behavior change. All contract tests green.

## Phase B2 — Composition configuration (Week 5–6)

### VEC-B2.1 — ProviderRegistry extension

- Description: Add `metadata.*` and `vector.*` namespaces resolving independently.
- Acceptance: Both namespaces resolve independently.
- Evidence: Registry tests.
- Estimate: 1 day.
- Depends on: VEC-B1.4.

### VEC-B2.2 — Startup validation + default preservation (merges old B2.2 + B2.3)

- Description: Reject incompatible compositions loudly (e.g. `vector.provider=milvus` without
  connectivity → named error). `vector.provider` defaults to `metadata.provider` when unset;
  single-provider stays zero-config.
- Acceptance: Every invalid combination → named error; existing configs work unchanged.
- Evidence: Validation tests per combination; regression test on existing config.
- Estimate: 1.5 days.
- Depends on: VEC-B2.1.

### VEC-B2.3 — Deployment config schema (old B2.4)

- Description: JSON/YAML schema for `synquest.metadata`, `synquest.vector`, `synquest.fusion`,
  `synquest.execution`, with examples.
- Acceptance: Schema validates; docs include examples.
- Evidence: Schema file + examples.
- Estimate: 4 hr.
- Depends on: VEC-B2.2.

Gate B2: Composition selectable in config; single-provider remains default.

## Phase B3 — Vector adapters (Week 6–8)

### VEC-B3.1 — MilvusVectorRetriever + MilvusVectorIndexWriter

- Description: Milvus adapter against Milvus container; implement port contract.
- Acceptance: Passes `VectorRetrieverContractTest`.
- Evidence: Contract suite green; sample artifact.
- Estimate: 3 days.
- Depends on: VEC-B1.4, BR-A0.2 (manifest shape for composition ID).

### VEC-B3.2 — QdrantVectorRetriever + QdrantVectorIndexWriter

- Description: Qdrant adapter. Same contract.
- Acceptance: Passes `VectorRetrieverContractTest`.
- Evidence: Contract suite green; sample artifact.
- Estimate: 3 days.
- Depends on: VEC-B1.4.

### VEC-B3.3 — LuceneStandalone + PgVector reference adapters (merges old B3.3 + B3.4)

- Description: Lucene as a vector engine without Cassandra storage (resolves R3 attribution
  ambiguity: same vector-only numbers as Cassandra+Lucene on identical queries). Wrap pgvector
  explicitly for reference-frame consistency (same numbers as current PG vector path).
- Acceptance: Comparison artifact; both pass contract/regression tests.
- Evidence: Comparison artifact + regression tests.
- Estimate: 2 days.
- Depends on: VEC-B1.4.

### VEC-B3.4 — Optional third-party adapter, parked (old B3.5, not counted in velocity)

- Description: Vespa or OpenSearch only if VEC-B0.4 identifies a context gap only they fill.
- Acceptance: Passes contract test if built.
- Evidence: Contract suite green.
- Estimate: 3 days.
- Depends on: VEC-B0.4, VEC-B1.4.

Gate B3: Milvus and Qdrant pass `VectorRetrieverContractTest`. R3 attribution ambiguity resolved.

## Phase B4 — Execution modes (Week 8–9)

### VEC-B4.1 — Parallel mode (default)

- Description: Lexical and vector retrievers run concurrently; fusion merges.
- Acceptance: Latency = max(legs) + fusion, measured empirically.
- Evidence: Comparison artifact.
- Estimate: 1 day.
- Depends on: VEC-B2.1.

### VEC-B4.2 — Sequential metadata-first + per-query override (merges old B4.2 + B4.3)

- Description: Lexical runs first with eligibility constraints → eligible set → vector retriever
  uses it as candidate universe. `SearchRequest.executionMode` overrides the deployment default.
- Acceptance: Result shape identical to parallel mode for non-binding filters; override honored.
- Evidence: Equivalence + override tests.
- Estimate: 2.5 days.
- Depends on: VEC-B4.1.

Gate B4: Both modes produce identical result shapes. Crossover selectivity documented.

## Phase B5 — 6-composition evaluation (Week 9–10)

Compositions: 1 Cassandra+Lucene · 2 Cassandra+Milvus · 3 Cassandra+Qdrant · 4 PG+Milvus ·
5 PG+Qdrant · 6 YDB+YDB (+ PG+pgvector as reference where noted).

### VEC-B5.1 — Compositions 1–6 manifest authoring

- Description: Author 6 manifests validating against BR-A0.2 schema.
- Acceptance: All 6 validate.
- Evidence: 6 manifest files.
- Estimate: 1 day.
- Depends on: BR-A0.2, VEC-B3.1, VEC-B3.2.

### VEC-B5.2 — Execute compositions 1–6

- Description: Run all 6 through the Benchmark Runner (or RunLeg fallback).
- Acceptance: 6 Result Manifests produced; hard gates pass on all 6.
- Evidence: 6 artifacts + convergence reports.
- Estimate: 2 days (wall time: days, active time minimal).
- Depends on: VEC-B5.1, BR-A2.1 (or RunLeg fallback).

### VEC-B5.3 — Effect isolation: engine + store + integration (merges old B5.3 + B5.4 + B5.5)

- Description: Vector-engine effect (rows 1 vs 2 vs 3); metadata-store effect (2 vs 4, 3 vs 5);
  integration vs composition (row 6 vs 2–5; PG+pgvector vs PG+Milvus/Qdrant).
- Acceptance: Each effect quantified with confidence bounds; "is integration competitive?" answered.
- Evidence: Comparison tables.
- Estimate: 1.5 days.
- Depends on: VEC-B5.2.

### VEC-B5.4 — Sequential vs parallel crossover (old B5.6)

- Description: Measure both execution modes across all four selectivity levels.
- Acceptance: Crossover selectivity identified.
- Evidence: Comparison chart.
- Estimate: 1 day.
- Depends on: VEC-B4.2, VEC-B5.2.

Gate B5: All 6 compositions have artifacts. Hard gates pass. Effects isolated.

## Phase B6 — Synthesis (Week 10–12)

### VEC-B6.1 — Populate matrix + update profiles (merges old B6.1 + B6.2)

- Description: Cross-reference VEC-B5 results into the VEC-B0 matrix (functional columns filled
  or flagged "unmeasured"); update all 5 context profiles with measured data.
- Acceptance: Every functional cell populated or flagged; each recommendation justified by
  non-functional + functional evidence.
- Evidence: Updated `vector-engine-selection.md` + profiles.
- Estimate: 2 days.
- Depends on: VEC-B5.3, VEC-B5.4.

### VEC-B6.2 — Publish framework + follow-ons (merges old B6.3 + B6.4)

- Description: Publish `docs/architecture/vector-engine-selection.md` (reviewed by architecture +
  legal); list every unmeasured engine-context pair and file owner-named follow-on tickets.
  Includes the expanded Rust follow-on below.
- Acceptance: Committed file; every unmeasured dimension has an owner-named follow-on.
- Evidence: Committed file + filed tickets.
- Estimate: 1 day.
- Depends on: VEC-B6.1.

VEC-B6.2-FOLLOWON — Rust lexical kernel (Tantivy/Quickwit) comparison:

Not scheduled. Activates when the trigger fires.

Trigger: one of the following passes `LexicalRetrieverContractTest`:

- `TantivySidecarRetriever` (custom Rust service exposing Tantivy over HTTP/gRPC)
- `QuickwitLexicalRetriever` (Java HTTP client against Quickwit REST API)

Scope: lexical retrieval only. Add a lexical-only comparison row to the B5 matrix subset.
Do not add as a vector-engine composition — the axes are independent.

Two paths, not one:

- Sidecar path: minimal Rust binary, one process per host, HTTP/gRPC. Best for single-host,
  embedded, small-cluster deployments.
- Quickwit path: distributed service with decoupled storage/compute, ES-compatible REST API.
  Best for cloud, multi-tenant, large-corpus deployments.

Choose based on deployment context. Both are valid; the trigger doesn't prefer one.

JNI excluded. No tantivy4java or custom JNI bridge. If a JNI approach is proposed later,
it goes through a separate architecture review, not this follow-on.

- Owner: Track B owner (or whoever builds the Rust adapter).
- Evidence required to close: comparison artifact showing lexical recall + latency for Lucene
  vs Tantivy-sidecar (or Quickwit), on the frozen v1 corpus, under the same R3 discipline.

Gate B6: Decision framework published. Follow-ons filed.
