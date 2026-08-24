# Synanton

**Synanton** is an open-source, AI-native enterprise knowledge platform. It unifies full-text search, dense vector search, knowledge-graph reasoning, and ontology management into a single modular system - ingesting enterprise content from heterogeneous sources, enriching it with LLMs, and exposing it through REST, gRPC, MCP, and agent-to-agent interfaces.

---

## What it does

```
Documents / APIs / Databases / S3
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│  Synvault (content store + tier manager)                  │
│  Synflux  (parse → chunk → enrich → embed → persist)      │
│  ingestion-cache (Cassandra artifact cache)               │
└───────────────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│  Synquest  BM25 + HNSW hybrid search kernel               │
│  Relix     GraphRAG engine (entity/relation graph)        │
│  Syntology ontology management (SHACL + versioning)       │
└───────────────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│  Planner  intent classification + query plan generation   │
│  Gateway  plan execution + LLM synthesis + reranking      │
│  Synapt   public REST/gRPC ingress + auth + rate limits   │
└───────────────────────────────────────────────────────────┘
        │
  REST / gRPC / MCP / ACP
```

A single query (`POST /search`) traverses the full stack: content is retrieved from the hybrid index, graph context is woven in by Relix, and the Gateway synthesises a natural-language answer citing the source chunks - all within per-tenant ACL boundaries.

---

## Purpose

Enterprise knowledge sits scattered across document stores, wikis, databases, and file shares. Extracting signal from it requires stitching together a search engine, an embedding pipeline, a graph database, and an LLM - each maintained separately and integrated ad-hoc.

Synanton provides a single coherent platform for this problem:

- **Ingest once.** A pluggable content adapter SPI handles S3, local filesystems, SharePoint, RDBMS, Kafka CDC feeds, and webhooks. Raw content moves through a staged pipeline that parses, chunks, enriches (two-pass LLM chain-of-thought), and embeds every document.
- **Query flexibly.** Hybrid BM25 + HNSW search, GraphRAG traversal, ontology-guided entity resolution, and cross-encoder reranking are combined by a planner into the optimal query plan for each question.
- **Answer accurately.** The Gateway synthesises a natural-language answer grounded in retrieved chunks, with citations, confidence scores, and execution traces attached.
- **Stay governed.** Per-tenant isolation, POSIX-backed ACL enforcement, GDPR erasure cascade, SHACL ontology validation, and per-tenant cost attribution are first-class concerns - not afterthoughts.

---

## Who it is for

| Use target | What Synanton provides |
|---|---|
| **Enterprise search teams** | A drop-in search backend that combines keyword and semantic retrieval without maintaining three separate systems. |
| **RAG application developers** | A managed ingestion pipeline (parse, chunk, enrich, embed) with a write-through artifact cache, plus a ready-made query-and-synthesis API. |
| **Knowledge management platforms** | Ontology management (Syntology), versioned schemas, SHACL validation, and a graph-based entity/relation store (Relix). |
| **AI agent builders** | An MCP-compatible tool surface (Synanton-MCP) and an agent-to-agent API (ACP), so Synanton becomes a callable knowledge tool for Claude, GPT-based agents, and custom agent frameworks. |
| **Security-conscious enterprises** | POSIX-backed file permissions, JWT-gated REST APIs, compile-time ACL injection, multi-tenant isolation tiers, and a full audit trail. |
| **Platform engineers** | A monorepo of independent Spring Boot services with hexagonal architecture, contract-first SPIs, Docker Compose and Kubernetes deployment profiles, and a GitOps-driven control plane. |

---

## Module map

| Module | Role | Status |
|---|---|---|
| `shared/common` | JWT verifier, error model, `TenantContext`, `MockTenantFilter` | ✅ Done |
| `ingestion-cache` | Cassandra DAO library - manifest, chunks, analysis, embeddings | ✅ Done |
| `synanton-llm-client` | Provider-agnostic LLM + embedding client (OpenAI-compat) | ✅ Done |
| `synvault` | Content store + adapter registry + MinIO/S3 facade | ✅ Done |
| `synflux` | Ingestion pipeline - acquire → parse → chunk → enrich → embed → persist | ✅ Done (Phase 1+2) |
| Lucentrix (sibling CLI) | Pluggable crawl CLI (web/dummy) that pushes raw bytes into synvault | ✅ Sibling repo |
| `security` | AuthN/Z, JWT issuance, htpasswd IdP, `FsPermissionGuard` | ✅ Done |
| `topology` | Org/ACL/policy store, `FilesystemAclSeeder`, JDBC repos | ✅ Done |
| `syntology` | Ontology service - REST API, Jena storage, HCL→JSON IR→SHACL, versioning | ✅ Done (standalone) |
| `syntology-admin` | React SPA - graph editor, login, grants view | ✅ Done (standalone) |
| `synquest` | Hybrid search kernel - BM25 + HNSW + RRF | ✅ Done (Phase 1) |
| `relix` | GraphRAG engine - JGraphT in-memory graph, MCP tools | ✅ Done (Phase 1) |
| `planner` | Intent classifier + query plan generator | ✅ Done (Phase 1) |
| `gateway` | Plan DAG executor + LLM synthesis + reranker + GPU execution client | ✅ Done (Phase 1+2; GPU client v1.20) |
| `synapt` | Public REST/gRPC ingress, rate limiting, sanitisation | ✅ Done (Phase 1+2) |
| `synflux-router` | Kafka-driven work distribution across synflux workers | ✅ Done (Phase 3) |
| `control-plane` | Admin API, forecast engine, anomaly detection, GitOps | ✅ Done (Phase 3 - admin API + ModelServingDirectory) |
| `synanton-mcp` | MCP protocol bridge - exposes platform tools to MCP clients | ✅ Done (Phase 3) |
| `synreview` | Human-in-the-loop review queue for low-confidence entities | 🔲 Phase 5 |

**GPU Execution Plane** (modules `java/gpu-contract` + `java/gpu-gateway` in this repo; extracted to `synanton/gpu-execution-plane` for independent deployment):

> Production GPU workloads (model serving, embedding, reranking) run in a physically isolated GPU cluster connected to this platform via the `synanton.gpu.v1` gRPC contract.

| Component | Role | Status |
|-----------|------|--------|
| `java/gpu-contract` | `synanton.gpu.v1` protobuf contract — `Execute`, `Cancel`, `GetStatus`, `GetCapacity` RPCs; structured error catalogue; generated gRPC stubs | ✅ GPU-1 done |
| `java/gpu-gateway` | GPU Gateway service — mTLS boundary, field validation, tenant assertion, idempotency store (PostgreSQL, fail-closed), `DirectDispatcher` → vLLM, execution lifecycle, Micrometer metrics | ✅ GPU-2 done |
| `gateway` GPU client | `GpuExecutionClient` + `GpuSynthesisAdapter` — primary platform gRPC client + synthesis adapter with `MODEL_NOT_READY` retry, CPU degraded fallback, trace context propagation; `ModelServingDirectory` `isGpuBacked()` / `getGpuModels()` | ✅ GPU-3 done |
| Consumer contract tests | `GpuContractTest` — in-process gRPC tests verifying all 4 RPCs, error shapes, and `MODEL_NOT_READY` retryable flag | ✅ GPU-1 done |
| `EqualixScheduler` | Optional fairness/quota scheduler (data-gated on GPU-4 evidence) | 🔲 GPU Phase 5 |

**Structured Content Extraction Plane** (module `java/extraction-contract` in this repo; implementation in `synanton/content_extractor`):

> Content extraction (PDF/text/EPUB/HTML now; audio, image, and video later) runs behind the `synanton.extraction.v1` gRPC contract. Deployment topology — embedded, co-located, or an independently scaled cluster — is a scaling concern that does not change the contract.

| Component | Role | Status |
|-----------|------|--------|
| `java/extraction-contract` | `synanton.extraction.v1` protobuf contract — 9 RPCs (submit, batch, sync, status poll, cursor completion poll, result, cancel, capacity, estimate, capabilities); 13-code error catalogue; explicit feature-state model; generated gRPC stubs | ✅ SCEP-1 done |
| Contract mirror check | `scripts/verify-contract-mirror.sh` + `verifyContractMirror` Gradle task, wired into `check` and CI — fails when the protos diverge from `content_extractor` | ✅ SCEP-1 done |
| Consumer contract tests | 43 tests: in-process gRPC round trip, idempotency replay, cursor polling, validation rules, and descriptor-level black-box enforcement (`ContractOpacityTest`) | ✅ SCEP-1 done |
| `java/extraction-client` | `ExtractionPlaneClient`, reconcile-after-timeout, `ExtractionFallbackPolicy` | 🔲 SCEP-5 |
| `synflux` extraction wiring | `ParseStage` re-pointed at the plane; in-process Tika retained as fallback; structure-aware chunking | 🔲 SCEP-5 |

---

## Roadmap

The platform is being built in five phases, with a GPU execution plane track introduced in v1.20 and a structured content extraction track in v1.21. Each phase ships a runnable demo and is fully additive - Phase N never breaks Phase N-1.

### Phase 1 - Foundation *(complete)*

**Delivers:** crawl a local folder → hybrid + graph search → return ranked hits (no LLM yet).

- [x] Monorepo scaffolding, shared library, ingestion-cache Cassandra schema
- [x] `synvault` - `FilesystemAdapter`, MinIO object store, manifest REST API
- [x] `synflux` - acquire → parse → chunk → persist pipeline, CLI + REST trigger
- [x] `synquest` - Lucene BM25 + HNSW hybrid search, boot-time index build
- [x] `relix` - JGraphT in-memory graph, `POST /graph/query`, three query shapes
- [x] `planner` - heuristic classifier (T1–T4), 4 plan templates, entity trie, `POST /plan`
- [x] `gateway` - plan DAG executor, parallel step dispatch, RRF fusion, `POST /query`
- [x] `synapt` - thin REST ingress, `MockTenantFilter`, Jakarta Validation, `POST /search`

**DoD:** `curl -X POST :8080/search -d '{"query":"who supplies Acme?"}'` returns ranked hits.

### Phase 2 - LLM online *(complete)*

**Delivers:** same query now returns an LLM-synthesised answer citing the hits.

- [x] `synanton-llm-client` - OpenAI-compat translator, retry logic, JSON schema validation
- [x] `synflux` - `EnrichStage` (Pass 1 per-chunk + Pass 2 per-doc) + `EmbedStage` (batch 32)
- [x] `ingestion-cache` - `analysis_cache`, `embedding_content_cache` schema + DAO extensions
- [x] `planner` - optional LLM-driven intent classification (feature-flagged)
- [x] `gateway` - LLM synthesis step, `answer` field in `QueryResponse` (enabled via `phase2` profile)
- [x] `synapt` - JWT/API-key auth seams, `X-Trace-Id` propagation, mock-tenant filter
- [x] `security` - first real implementation: JWT issuance, htpasswd backend, `FsPermissionGuard`
- [x] `topology` - first real implementation: PostgreSQL schema, `FilesystemAclSeeder`, `TopologyMutationApi`

**DoD:** `POST /search` returns `QueryResponse.answer` ≥ 20 words, coherent with hits.

### Phase 3 - Multi-tenant, auth, router *(complete)*

**Delivers:** multiple tenants, real users, Kafka-decoupled ingestion, MCP surface.

- [x] Kafka-driven `synflux-router` - replaces inline crawl with topic-based dispatch
- [x] `security` - RFC 8693 outbound auth broker, API key lifecycle (`ApiKeyController`, `TokenExchangeController`)
- [x] `topology` - grant/revoke API, outbox dispatcher (`TopologyOutboxDispatcher`, `JdbcGrantRepository`)
- [x] `control-plane` - first real implementation: admin API, `ModelServingDirectory`
- [x] `synanton-mcp` - MCP STREAMABLE_HTTP: `search`, `graph_query`, `ontology_resolve`

**DoD:** two-tenant demo, one API key per tenant, external MCP client (Claude Desktop) returns tenant-scoped hits.

### Phase 4 - Production hardening 

**Delivers:** enterprise security posture: ACL enforced everywhere, XSS protection, reranking, rate limits.

- [x] Compile-time ACL injection (three-layer enforcement per §40)
- [x] Global JSON sanitisation (OWASP Jackson deserialiser)
- [x] CSP headers + `X-Frame-Options: DENY`
- [x] Cross-encoder reranker (BGE reranker base, `VllmCrossEncoderRerankAdapter`)
- [x] `synquest` - Cuckoo ACL filter, incremental index updates, hot-shard rebalancing
- [x] Prometheus + Alertmanager + Grafana dashboards

### Phase 5 - Scale + vision + DR *(planned)*

**Delivers:** multimodal ingest, long-term storage tiering, Rust hot loop, GDPR erasure, disaster recovery.

- Vision captioning stage (LLaVA / Qwen2-VL)
- HOT → WARM → COLD → Glacier tier movement in `synvault`
- `synquest` Rust hot loop migration (JNI boundary, drop-in replacement)
- GDPR erasure cascade - tombstone + `source_ref_count` CAS
- `synreview` - human-in-the-loop review queue, 24-hour staging, replay
- Cross-region DR within RTO/RPO SLOs

### GPU Execution Plane track - v1.20 *(planned, separate repository)*

**Delivers:** physically isolated GPU cluster with a versioned gRPC contract between the primary platform and GPU infrastructure.

> **Note:** The Phase 2 Quick Start below runs vLLM locally for development and demo purposes. That is **not** the production GPU Execution Plane. Production GPU workloads use the `synanton/gpu-execution-plane` repository and connect via `synanton.gpu.v1` over mTLS.

- **GPU-1** ✅ - `synanton.gpu.v1` contract (`java/gpu-contract`), structured error catalogue, generated stubs, consumer contract tests (`GpuContractTest`), `GpuExecutionClient` in `gateway`
- **GPU-2** ✅ - GPU Gateway (`java/gpu-gateway`): `GpuExecutionServiceImpl`, `DirectDispatcher`, durable PostgreSQL idempotency store (fail-closed), `TenantAssertionValidator`, `GpuGatewayMetrics`, Flyway migration
- **GPU-3** ✅ - `GpuSynthesisAdapter` (retry + CPU degraded fallback), wired into `SynthesisService`; `ModelServingDirectory` `isGpuBacked()` / `getGpuModels()`; trace context propagation; `gateway.gpu.*` config; llama model marked `execution-plane: gpu`
- **GPU-4** 🔲 - Security hardening, failure injection tests, idempotency/duplicate tests, observability dashboards, cost attribution validation
- **GPU-5** 🔲 *(conditional)* - `EqualixScheduler` — only after GPU-4 operational evidence

See `docs/implementation/gpu-execution-plane/INDEX.md` for the detailed implementation plan.

### Structured Content Extraction Plane track - v1.21 *(in progress, separate repository)*

**Delivers:** structured content extraction behind a deployment-neutral contract. Consumers receive reading order, headings, tables, and page provenance instead of a flat string, and never reparse the source to obtain text.

> **Architectural decision:** the extraction plane is a black box. The platform specifies *what* to extract and under *what constraints*; the plane decides *how*. Which parser runs, whether OCR is local or remote, and whether work is CPU-, GPU-, or accelerator-backed are invisible through the contract — so the implementation can grow from an embedded processor into a distributed cluster without a platform API change.

- **SCEP-1** ✅ - `synanton.extraction.v1` contract (`java/extraction-contract`), byte-identical mirror in `synanton/content_extractor` with a drift check wired into `check` and CI, 13-code error catalogue with retryability verdicts, explicit feature-state model, 43 consumer contract tests
- **SCEP-2** 🔲 - Extraction plane skeleton + sync path: gRPC server, `ModalityAdapter` SPI, router, text/EPUB/HTML adapter, sandbox limits
- **SCEP-3** 🔲 - PDF PoC via OpenDataLoader behind the adapter boundary; the 15 acceptance criteria of the design draft
- **SCEP-4** 🔲 - Async operation model: PostgreSQL operation store, fail-closed idempotency, admission under advisory lock, expiry semantics, cursor completion feed
- **SCEP-5** 🔲 - Platform integration: `extraction-client`, `synflux` `ParseStage` re-pointed at the plane with Tika fallback retained, structure-aware chunking
- **SCEP-6** 🔲 - Topology equivalence proof (embedded vs remote produce equivalent semantics) + security hardening; gates v1.21 completion
- **SCEP-7** 🔲 *(post-v1.21)* - Audio, image, and video extraction; additive, no contract change

See `docs/implementation/content-extraction-plane/INDEX.md` for the detailed implementation plan and `docs/implementation/content-extraction-plane/error-catalogue.md` for the error contract.

---

## Quick start

### Ingestion demo (Phase 1 - no GPU required)

```bash
# Prerequisites: Docker, Java 21, Gradle
cp .env.example .env

# Start Cassandra + MinIO + synvault + synflux
docker compose -f deployment/docker/compose.yaml up -d --build \
  cassandra minio minio-init synvault synflux

# Ingest demo-data/documents/
./scripts/run-ingestion-demo.sh --phase=1

# Inspect the manifest
curl http://localhost:8091/manifest/demo | python3 -m json.tool
```

### Ingestion demo with LLM enrichment (Phase 2 - requires 2× 8 GB GPU)

> **Note:** This demo runs vLLM locally in Docker for development and evaluation only. Production GPU inference uses the separate `synanton/gpu-execution-plane` repository connected via `synanton.gpu.v1` over mTLS.

```bash
# Download models (~6 GB, needs HF token for Llama 3.1)
export HF_TOKEN=<your_token>
docker compose -f deployment/docker/compose.yaml --profile phase2 pull

./scripts/run-ingestion-demo.sh --phase=2
```

### Syntology standalone demo (ontology admin)

```bash
./scripts/setup-dev.sh
./gradlew :java:syntology:bootRun
# Open http://localhost:8080
```

### Full demo stack (security + topology + syntology + admin UI)

```bash
cp .env.example .env   # set SYNANTON_JWT_SECRET and POSTGRES_PASSWORD
./scripts/run-demo.sh  # docker compose up --build
# Open http://localhost:8080
# Login as alice (writes allowed) or bob (read-only)
```

---

## Repository layout

```
java/
  shared/common/        JWT verifier, error model, TenantContext (shared library)
  ingestion-cache/      Cassandra DAO library - manifest, chunks, analysis, embeddings
  synanton-llm-client/  Provider-agnostic LLM + embedding HTTP client
  synvault/             Content store - FilesystemAdapter, MinIO facade, manifest REST
  synflux/              Ingestion pipeline - stages, job runner, CLI, REST trigger
  security/             AuthN/Z service - htpasswd + JWT issuance + FsPermissionGuard
  topology/             Org/user/ACL store - JDBC repos, Flyway, FilesystemAclSeeder
  syntology/            Ontology service - REST API + Jena TDB2 + SHACL
  synquest/             Hybrid search kernel (Lucene 9 BM25 + HNSW)
  relix/                GraphRAG engine (JGraphT, MCP tools)
  planner/              Query intent classifier + plan generator
  gateway/              Plan DAG executor + LLM synthesis
  synapt/               Public REST/gRPC ingress, rate limiting
  control-plane/        Admin API, forecast, anomaly, GitOps
  synflux-router/       Kafka-driven ingestion work distributor
  synanton-mcp/         MCP protocol bridge
  gpu-contract/         synanton.gpu.v1 protobuf contract (v1.20)
  gpu-gateway/          GPU Execution Plane service (v1.20)
  extraction-contract/  synanton.extraction.v1 protobuf contract (v1.21),
                        mirrored byte-for-byte in synanton/content_extractor

rust/                   Future Rust components (synquest hot loop - Phase 5)

ui/
  syntology-admin/      React SPA - graph editor, login, grants view, SHACL panel

deployment/
  docker/               Docker Compose stack and Dockerfiles for all services

docs/
  architecture/         Platform design (v1.19), module contracts, decision records
  implementation/       Phase-by-phase implementation plans (Phases 1–3 complete)
  proposals/            Versioned change proposals (v1.20 GPU, v1.21 extraction)

scripts/
  setup-dev.sh          Toolchain check (Java 21, Docker, Node 20)
  run-demo.sh           Full demo stack via docker compose
  run-ingestion-demo.sh Ingestion-only demo (Phase 1 or Phase 2)
  build-all.sh          Build every active module
  verify-contract-mirror.sh  Fail if synanton.extraction.v1 diverges from
                        the content_extractor copy (runs in `check` and CI)

demo-data/
  documents/            10 synthetic markdown/text files for ingestion demo
  ontologies/           Sample Turtle/RDF files for syntology demo
  users/                htpasswd seed file (alice, bob, admin)

test/
  e2e/                  Playwright acceptance tests (planned - Phase 1 DoD)
```

---

## Architecture overview

Synanton follows a **hexagonal (ports-and-adapters)** architecture. Each module owns:

- An **inbound port** - the REST/gRPC/MCP surface the module exposes.
- A set of **outbound ports** - SPIs the module calls without knowing the implementation.
- **Adapters** - concrete implementations of those SPIs (e.g. `FilesystemAdapter`, `MinioObjectStoreAdapter`, `JenaTdb2OntologyAdapter`).

This means every adapter is swappable without touching domain logic. `FilesystemAdapter` is replaced by `S3Adapter` by configuration alone. `InMemoryGraphConnector` in Relix is replaced by `Neo4jConnector` in Phase 3.

**Key SPIs defined:**

| SPI | Where | Purpose |
|---|---|---|
| `ContentAdapter` | `synvault` | Pluggable document source (filesystem, S3, SharePoint, webhooks) |
| `OntologyAdapter` | `syntology` | Pluggable RDF store (Jena TDB2, GraphDB, RDF4J) |
| `GraphConnectorSPI` | `relix` | Pluggable graph backend (in-memory, Neo4j, Neptune) |
| `RerankerPort` | `gateway` | Cross-encoder reranker (vLLM, Cohere, custom) |
| `IdentityProviderPort` | `security` | IdP backend (htpasswd, Keycloak, OIDC) |
| `LlmClient` | `synanton-llm-client` | LLM provider (vLLM, OpenAI, Anthropic, Bedrock) |

**Cross-cutting concerns built in from day one:**
- Multi-tenancy via `TenantContext` thread-local propagated on every request.
- Compile-time ACL injection - search and graph queries carry a tenant-scope filter before reaching storage.
- Per-tenant cost attribution - LLM calls, object-store bytes, and index queries are metered and budgeted.
- GDPR erasure cascade - tombstoning a document propagates through manifest, chunks, embeddings, analysis, and graph nodes.

---

## Development

### Prerequisites

- Java 21 (Temurin recommended)
- Docker 24+ with Compose V2
- Node 20 + pnpm 9 (UI only)
- NVIDIA Container Toolkit (Phase 2 GPU pipeline only)

### Build

```bash
./gradlew build           # all active Java modules
cd ui/syntology-admin && pnpm install && pnpm build
```

### Test

```bash
./gradlew test            # unit tests (no Docker required)
./gradlew acceptanceTest  # acceptance tests (requires Docker)
```

### Environment

Copy `.env.example` and set at minimum:

```
SYNANTON_JWT_SECRET=<at-least-32-random-bytes>
POSTGRES_PASSWORD=<your-choice>
MINIO_ROOT_PASSWORD=<your-choice>
```

---

## Documentation

| Document | Location |
|---|---|
| Platform architecture (v1.20) | `docs/architecture/synanton-design-1.20.md` |
| Platform architecture (v1.19, baseline) | `docs/architecture/synanton-design-1.19.md` |
| GPU Execution Plane implementation plan | `docs/implementation/gpu-execution-plane/INDEX.md` |
| Phases master plan | `docs/implementation/synanton-phases-plan.md` |
| Phase 1 ingestion plan | `docs/implementation/phase1/01-ingestion-pipeline.md` |
| Phase 2 LLM enrichment plan | `docs/implementation/phase2/01-ingestion-pipeline.md` |
| Syntology standalone demo | `docs/implementation/demo/standalone-syntology-demo.md` |
| Lucentrix ingest CLI | `docs/implementation/lucentrix-ingest-cli.md` |

---

## License

Apache 2.0 - see [LICENSE](LICENSE).
